package com.redismini.persistence;

import com.redismini.TestClock;
import com.redismini.command.CommandRegistry;
import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AofWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesFsyncPoliciesCaseInsensitively() {
        assertThat(FsyncPolicy.fromString("always")).isEqualTo(FsyncPolicy.ALWAYS);
        assertThat(FsyncPolicy.fromString("EVERYSEC")).isEqualTo(FsyncPolicy.EVERYSEC);
        assertThat(FsyncPolicy.fromString("No")).isEqualTo(FsyncPolicy.NO);

        assertThatThrownBy(() -> FsyncPolicy.fromString("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendsCommandsInRespFormatAndPreservesExistingContent() throws IOException {
        Path aofFile = tempDir.resolve("appendonly.aof");

        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            writer.append(List.of(BulkString.of("SET"), BulkString.of("k1"), BulkString.of("v1")));
            writer.append(List.of(BulkString.of("INCR"), BulkString.of("counter")));
        }

        // Mở lại để append thêm -> không ghi đè dữ liệu cũ
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.NO)) {
            byte[] binary = {0, 1, '\r', '\n', (byte) 0xFF};
            writer.append(List.of(BulkString.of("SET"), BulkString.of("bin"), new BulkString(binary)));
        }

        List<List<BulkString>> parsed = readAllCommands(aofFile);
        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0)).containsExactly(BulkString.of("SET"), BulkString.of("k1"), BulkString.of("v1"));
        assertThat(parsed.get(1)).containsExactly(BulkString.of("INCR"), BulkString.of("counter"));
        assertThat(parsed.get(2)).containsExactly(
                BulkString.of("SET"),
                BulkString.of("bin"),
                new BulkString(new byte[]{0, 1, '\r', '\n', (byte) 0xFF})
        );
    }

    @Test
    void everysecSyncIfDirtyTracksPendingWrites() throws IOException {
        Path aofFile = tempDir.resolve("everysec.aof");
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.EVERYSEC, null)) {
            assertThat(writer.syncIfDirty()).isFalse();

            writer.append(List.of(BulkString.of("SET"), BulkString.of("a"), BulkString.of("1")));
            assertThat(writer.syncIfDirty()).isTrue();
            assertThat(writer.syncIfDirty()).isFalse();
        }
    }

    @Test
    void concurrentAppendsDoNotCorruptRespRecords() throws Exception {
        Path aofFile = tempDir.resolve("concurrent.aof");
        int threads = 8;
        int writesPerThread = 50;

        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.NO);
             ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            writer.append(List.of(
                                    BulkString.of("SET"),
                                    BulkString.of("k_" + threadId + "_" + i),
                                    BulkString.of("val_" + i)
                            ));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await();
        }

        List<List<BulkString>> parsed = readAllCommands(aofFile);
        assertThat(parsed).hasSize(threads * writesPerThread);
    }

    @Test
    void throwsWhenAppendingAfterClose() throws IOException {
        Path aofFile = tempDir.resolve("closed.aof");
        AofWriter writer = new AofWriter(aofFile, FsyncPolicy.NO);
        writer.close();

        assertThatThrownBy(() -> writer.append(List.of(BulkString.of("SET"), BulkString.of("k"), BulkString.of("v"))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void backgroundRewriteCompactsRedundantCommandsAndPreservesTtl() throws Exception {
        Path aofFile = tempDir.resolve("compact.aof");
        TestClock clock = TestClock.ofEpochMilli(1_000_000L);
        DataStore store = new DataStore(clock);

        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(store, writer);
            for (int i = 0; i < 20; i++) {
                registry.execute(cmd("SET", "overwrite", "val_" + i));
            }
            for (int i = 0; i < 10; i++) {
                registry.execute(cmd("INCR", "counter"));
            }
            registry.execute(cmd("SET", "deleted", "tmp"));
            registry.execute(cmd("DEL", "deleted"));
            registry.execute(cmd("SET", "ttl_key", "alive", "EX", "30"));

            // Trước khi rewrite có 33 lệnh
            assertThat(readAllCommands(aofFile)).hasSize(33);

            assertThat(writer.startBackgroundRewrite(store)).isTrue();
            writer.getRewriteFuture().get(5, TimeUnit.SECONDS);

            // Sau rewrite chỉ còn 3 lệnh đại diện trạng thái hiện tại
            List<List<BulkString>> compacted = readAllCommands(aofFile);
            assertThat(compacted).hasSize(3);

            // Tiếp tục ghi sau khi rewrite vẫn hoạt động bình thường
            registry.execute(cmd("SET", "after", "ok"));
        }

        // Load lại vào DataStore mới và kiểm tra toàn vẹn dữ liệu + TTL
        DataStore restored = new DataStore(clock);
        assertThat(AofLoader.load(aofFile, CommandRegistry.createDefault(restored))).isEqualTo(4);
        assertThat(restored.get("overwrite")).isEqualTo("val_19".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("counter")).isEqualTo("10".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.exists("deleted")).isFalse();
        assertThat(restored.get("ttl_key")).isEqualTo("alive".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.getTtlSeconds("ttl_key")).isEqualTo(30);
        assertThat(restored.get("after")).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void backgroundRewritePreservesConcurrentWritesWithoutDuplication() throws Exception {
        Path aofFile = tempDir.resolve("concurrent_rewrite.aof");
        DataStore store = new DataStore();

        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.NO)) {
            CommandRegistry registry = CommandRegistry.createDefault(store, writer);
            for (int i = 0; i < 200; i++) {
                registry.execute(cmd("SET", "k_" + i, "v_" + i));
            }
            registry.execute(cmd("SET", "counter", "0"));

            assertThat(writer.startBackgroundRewrite(store)).isTrue();
            // Ghi INCR và SET song song trong lúc background rewrite đang chạy
            for (int i = 0; i < 25; i++) {
                registry.execute(cmd("INCR", "counter"));
            }
            registry.execute(cmd("SET", "during_rewrite", "yes"));

            writer.getRewriteFuture().get(5, TimeUnit.SECONDS);
        }

        DataStore restored = new DataStore();
        AofLoader.load(aofFile, CommandRegistry.createDefault(restored));
        assertThat(restored.get("counter")).isEqualTo("25".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("during_rewrite")).isEqualTo("yes".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("k_199")).isEqualTo("v_199".getBytes(StandardCharsets.UTF_8));
    }

    private static List<BulkString> cmd(String... parts) {
        return Arrays.stream(parts).map(BulkString::of).toList();
    }

    private static List<List<BulkString>> readAllCommands(Path aofFile) throws IOException {
        List<List<BulkString>> result = new ArrayList<>();
        try (var in = new BufferedInputStream(Files.newInputStream(aofFile))) {
            RespParser parser = new RespParser(in);
            List<BulkString> cmd;
            while ((cmd = parser.readCommand()) != null) {
                result.add(cmd);
            }
        }
        return result;
    }
}

