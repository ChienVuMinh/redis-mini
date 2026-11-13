package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.protocol.NonBlockingRespDecoder;
import com.redismini.protocol.RespProtocolException;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespWriter;
import com.redismini.store.ExpiryManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

/**
 * Event loop đơn luồng sử dụng Java NIO ({@link ServerSocketChannel} non-blocking + {@link Selector}).
 * <p>
 * Một thread duy nhất xử lý accept, đọc/ghi không chặn cho hàng nghìn kết nối đồng thời,
 * thực thi lệnh tuần tự và chạy active eviction định kỳ mà không cần tạo thread cho từng client.
 */
public class NioServer implements AutoCloseable {

    private static final int INITIAL_READ_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_READ_BUFFER_SIZE = 64 * 1024 * 1024;
    private static final int BACKLOG = 1024;

    private final int port;
    private final CommandRegistry commands;
    private final ExpiryManager expiryManager;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private int boundPort;
    private volatile boolean running;
    private volatile boolean serving;
    private volatile boolean closed;

    public NioServer(int port, CommandRegistry commands) {
        this(port, commands, null);
    }

    public NioServer(int port, CommandRegistry commands, ExpiryManager expiryManager) {
        this.port = port;
        this.commands = commands;
        this.expiryManager = expiryManager;
    }

    /** Mở cổng rồi chạy event loop cho đến khi {@link #close()}. Chặn thread gọi hàm này. */
    public void start() throws IOException {
        bind();
        serve();
    }

    /** Mở cổng non-blocking và đăng ký OP_ACCEPT lên Selector. */
    public void bind() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port), BACKLOG);
        boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        System.out.println("NIO event loop listening on port " + getPort());
    }

    /** Chạy vòng lặp sự kiện (Event Loop) trên thread hiện tại cho đến khi {@link #close()}. */
    public void serve() throws IOException {
        serving = true;
        long lastEvictionTime = System.currentTimeMillis();
        long selectTimeoutMs = expiryManager != null ? ExpiryManager.DEFAULT_PERIOD_MS : 0L;

        try {
            while (running) {
                selector.select(selectTimeoutMs);
                if (!running) {
                    break;
                }

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        handleAccept();
                    } else {
                        if (key.isValid() && key.isReadable()) {
                            handleRead(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key);
                        }
                    }
                }

                if (expiryManager != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastEvictionTime >= ExpiryManager.DEFAULT_PERIOD_MS) {
                        expiryManager.runEvictionCycle();
                        lastEvictionTime = now;
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            if (running) {
                throw e;
            }
        } finally {
            closeResources();
        }
    }

    private void handleAccept() throws IOException {
        while (running) {
            SocketChannel client = serverChannel.accept();
            if (client == null) {
                break;
            }
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            client.register(selector, SelectionKey.OP_READ, new ClientSession(client));
        }
    }

    private void handleRead(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        try {
            try {
                if (!session.readBuffer.hasRemaining()) {
                    session.growReadBuffer();
                }

                int bytesRead = session.channel.read(session.readBuffer);
                if (bytesRead == -1) {
                    closeConnection(key, session);
                    return;
                }
                if (bytesRead == 0) {
                    return;
                }

                session.readBuffer.flip();
                List<BulkString> command;
                while ((command = NonBlockingRespDecoder.tryReadCommand(session.readBuffer)) != null) {
                    RespValue reply = commands.execute(command);
                    enqueueReply(key, session, reply);
                    if (!key.isValid()) {
                        return;
                    }
                }
                session.readBuffer.compact();
            } catch (RespProtocolException e) {
                session.closeAfterWrite = true;
                if (key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                }
                enqueueReply(key, session, new SimpleError("ERR Protocol error: " + e.getMessage()));
            }
        } catch (IOException e) {
            closeConnection(key, session);
        }
    }

    private void enqueueReply(SelectionKey key, ClientSession session, RespValue reply) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(RespWriter.encode(reply));
        if (session.writeQueue.isEmpty()) {
            // Fast path: ghi thẳng nếu OS send buffer còn chỗ
            session.channel.write(buffer);
            if (!buffer.hasRemaining()) {
                if (session.closeAfterWrite) {
                    closeConnection(key, session);
                }
                return;
            }
        }
        session.writeQueue.add(buffer);
        if (key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        try {
            while (!session.writeQueue.isEmpty()) {
                ByteBuffer head = session.writeQueue.peek();
                session.channel.write(head);
                if (head.hasRemaining()) {
                    return;
                }
                session.writeQueue.poll();
            }

            if (session.closeAfterWrite) {
                closeConnection(key, session);
            } else if (key.isValid()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            closeConnection(key, session);
        }
    }

    private static void closeConnection(SelectionKey key, ClientSession session) {
        key.cancel();
        try {
            session.channel.close();
        } catch (IOException ignored) {
        }
    }

    public int getPort() {
        return boundPort;
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        if (!serving) {
            closeResources();
        }
    }

    private synchronized void closeResources() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        if (expiryManager != null) {
            expiryManager.close();
        }
        if (selector != null && selector.isOpen()) {
            for (SelectionKey key : selector.keys()) {
                try {
                    key.channel().close();
                } catch (IOException ignored) {
                }
            }
            selector.close();
        }
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
    }

    private static final class ClientSession {
        final SocketChannel channel;
        ByteBuffer readBuffer = ByteBuffer.allocate(INITIAL_READ_BUFFER_SIZE);
        final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
        boolean closeAfterWrite;

        ClientSession(SocketChannel channel) {
            this.channel = channel;
        }

        void growReadBuffer() throws RespProtocolException {
            int currentCapacity = readBuffer.capacity();
            if (currentCapacity >= MAX_READ_BUFFER_SIZE) {
                throw new RespProtocolException("Command buffer exceeded maximum size");
            }
            int newCapacity = Math.min(currentCapacity * 2, MAX_READ_BUFFER_SIZE);
            ByteBuffer expanded = ByteBuffer.allocate(newCapacity);
            readBuffer.flip();
            expanded.put(readBuffer);
            readBuffer = expanded;
        }
    }
}
