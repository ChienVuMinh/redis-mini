package com.redismini.persistence;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespWriter;
import com.redismini.store.DataStore;
import com.redismini.store.Entry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ghi các lệnh thay đổi dữ liệu vào file AOF (Append-Only File) theo định dạng RESP.
 * Hỗ trợ 3 chế độ fsync: {@code always}, {@code everysec}, {@code no} và {@code BGREWRITEAOF}.
 */
public class AofWriter implements AutoCloseable {

    public static final long EVERYSEC_INTERVAL_MS = 1000L;

    private final Path path;
    private final FsyncPolicy policy;
    private FileChannel channel;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private boolean dirty;
    private boolean started;
    private boolean closed;
    private boolean rewriting;
    private List<byte[]> rewriteBuffer;
    private volatile CompletableFuture<Void> rewriteFuture;

    public AofWriter(Path path, FsyncPolicy policy) throws IOException {
        this(path, policy, policy == FsyncPolicy.EVERYSEC ? createDefaultScheduler() : null,
                policy == FsyncPolicy.EVERYSEC);
    }

    public AofWriter(Path path, FsyncPolicy policy, ScheduledExecutorService scheduler) throws IOException {
        this(path, policy, scheduler, false);
    }

    private AofWriter(Path path, FsyncPolicy policy, ScheduledExecutorService scheduler, boolean ownsScheduler)
            throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.channel = openAppendChannel(path);
    }

    private static FileChannel openAppendChannel(Path path) throws IOException {
        return FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    private static ScheduledExecutorService createDefaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aof-fsync");
            t.setDaemon(true);
            return t;
        });
    }

    /** Bắt đầu lịch fsync định kỳ (nếu chế độ là {@link FsyncPolicy#EVERYSEC}). */
    public synchronized void start() {
        if (closed || started) {
            return;
        }
        started = true;
        if (policy == FsyncPolicy.EVERYSEC && scheduler != null) {
            scheduler.scheduleWithFixedDelay(
                    this::syncSafely,
                    EVERYSEC_INTERVAL_MS,
                    EVERYSEC_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Ghi một lệnh vào cuối file AOF dưới dạng RESP Array.
     *
     * @param command danh sách phần tử của lệnh (phần tử đầu là tên lệnh)
     */
    public synchronized void append(List<BulkString> command) throws IOException {
        if (closed) {
            throw new IOException("AofWriter is closed");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }

        byte[] encoded = encodeCommand(command);
        writeFully(channel, encoded);
        if (rewriting && rewriteBuffer != null) {
            rewriteBuffer.add(encoded);
        }

        if (policy == FsyncPolicy.ALWAYS) {
            channel.force(false);
            dirty = false;
        } else if (policy == FsyncPolicy.EVERYSEC) {
            dirty = true;
        }
    }

    /**
     * Bắt đầu viết lại file AOF trên background thread để chỉ giữ trạng thái hiện tại của DataStore.
     * Mọi lệnh ghi phát sinh trong quá trình rewrite sẽ được đệm vào {@code rewriteBuffer} và ghép vào cuối file mới.
     *
     * @return {@code true} nếu bắt đầu thành công, {@code false} nếu đang có một tiến trình rewrite khác chạy (hoặc writer đã đóng)
     */
    public synchronized boolean startBackgroundRewrite(DataStore store) {
        Objects.requireNonNull(store, "store");
        if (closed || rewriting) {
            return false;
        }
        rewriting = true;
        rewriteBuffer = new ArrayList<>();
        Map<String, Entry> snapshot = store.snapshot();
        long nowMillis = store.getClock().millis();
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.rewriteFuture = future;

        Thread rewriteThread = new Thread(() -> performRewrite(snapshot, nowMillis, future), "aof-rewrite");
        rewriteThread.setDaemon(true);
        rewriteThread.start();
        return true;
    }

    private void performRewrite(Map<String, Entry> snapshot, long nowMillis, CompletableFuture<Void> future) {
        Path tempPath = path.resolveSibling(path.getFileName() + ".rewrite.tmp");
        try {
            try (FileChannel tempChannel = FileChannel.open(
                    tempPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                for (Map.Entry<String, Entry> entryItem : snapshot.entrySet()) {
                    Entry entry = entryItem.getValue();
                    if (entry.isExpired(nowMillis)) {
                        continue;
                    }
                    writeFully(tempChannel, encodeCommand(buildSnapshotCommand(entryItem.getKey(), entry)));
                }

                synchronized (this) {
                    if (closed) {
                        rewriting = false;
                        rewriteBuffer = null;
                        future.complete(null);
                        return;
                    }
                    for (byte[] bufferedCmd : rewriteBuffer) {
                        writeFully(tempChannel, bufferedCmd);
                    }
                    tempChannel.force(true);
                    tempChannel.close();

                    channel.close();
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
                    this.channel = openAppendChannel(path);
                    this.dirty = false;
                    this.rewriting = false;
                    this.rewriteBuffer = null;
                }
            }
            future.complete(null);
        } catch (Throwable t) {
            synchronized (this) {
                this.rewriting = false;
                this.rewriteBuffer = null;
                if (!closed && (channel == null || !channel.isOpen())) {
                    try {
                        this.channel = openAppendChannel(path);
                    } catch (IOException reopenEx) {
                        System.err.println("Failed to reopen AOF channel after rewrite error: " + reopenEx.getMessage());
                    }
                }
            }
            future.completeExceptionally(t);
        } finally {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
        }
    }

    private static List<BulkString> buildSnapshotCommand(String key, Entry entry) {
        if (entry.expireAt() == null) {
            return List.of(BulkString.of("SET"), BulkString.of(key), new BulkString(entry.value()));
        }
        return List.of(
                BulkString.of("SET"),
                BulkString.of(key),
                new BulkString(entry.value()),
                BulkString.of("PXAT"),
                BulkString.of(Long.toString(entry.expireAt()))
        );
    }

    private static byte[] encodeCommand(List<BulkString> command) {
        return RespWriter.encode(new RespArray(List.<RespValue>copyOf(command)));
    }

    private static void writeFully(FileChannel target, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            target.write(buffer);
        }
    }

    public synchronized boolean isRewriting() {
        return rewriting;
    }

    /** Future của lần rewrite gần nhất (chủ yếu dùng cho test đợi hoàn thành). */
    public CompletableFuture<Void> getRewriteFuture() {
        return rewriteFuture;
    }

    /** Thực hiện fsync nếu có dữ liệu mới chưa được đồng bộ (dùng cho chế độ EVERYSEC). */
    public synchronized boolean syncIfDirty() throws IOException {
        if (closed || !dirty) {
            return false;
        }
        channel.force(false);
        dirty = false;
        return true;
    }

    /** Chủ động fsync toàn bộ dữ liệu đang đệm xuống đĩa. */
    public synchronized void flush() throws IOException {
        if (closed) {
            return;
        }
        channel.force(false);
        dirty = false;
    }

    private void syncSafely() {
        try {
            syncIfDirty();
        } catch (IOException e) {
            System.err.println("Error during AOF fsync: " + e.getMessage());
        }
    }

    public Path getPath() {
        return path;
    }

    public FsyncPolicy getPolicy() {
        return policy;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsScheduler && scheduler != null) {
            scheduler.shutdownNow();
        }
        try {
            channel.force(true);
            dirty = false;
        } finally {
            channel.close();
        }
    }
}

