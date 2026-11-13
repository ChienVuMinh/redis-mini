package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.protocol.RespWriter;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm thử cho {@link NioServer} (event loop đơn luồng với Selector).
 */
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class NioServerTest {

    private DataStore store;
    private NioServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        store = new DataStore();
        server = new NioServer(0, CommandRegistry.createDefault(store));
        server.bind();
        serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "nio-event-loop");
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        serverThread.join();
    }

    @Test
    void repliesPongToPingAndHandlesInlineCommands() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING")).isEqualTo(new SimpleString("PONG"));
            assertThat(client.send("ping", "xin chao")).isEqualTo(BulkString.of("xin chao"));

            client.sendRaw("PING\r\n");
            assertThat(client.readReply()).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void handlesPipelinedCommandsInSinglePacket() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*2\r\n$4\r\nECHO\r\n$1\r\na\r\n*2\r\n$4\r\nECHO\r\n$1\r\nb\r\n");

            assertThat(client.readReply()).isEqualTo(BulkString.of("a"));
            assertThat(client.readReply()).isEqualTo(BulkString.of("b"));
        }
    }

    @Test
    void handlesCommandSplitAcrossPackets() throws Exception {
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*2\r\n$4\r\nPI");
            Thread.sleep(50);
            client.sendRaw("NG\r\n$2\r\nhi\r\n");

            assertThat(client.readReply()).isEqualTo(BulkString.of("hi"));
        }
    }

    @Test
    void handlesLargePayloadLargerThanInitialReadBuffer() throws IOException {
        String largeValue = "x".repeat(20_000);
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("SET", "big", largeValue)).isEqualTo(new SimpleString("OK"));
            assertThat(client.send("GET", "big")).isEqualTo(BulkString.of(largeValue));
        }
    }

    @Test
    void protocolErrorRepliesThenClosesConnection() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*abc\r\n");
            assertThat(client.readReply()).isInstanceOfSatisfying(SimpleError.class,
                    error -> assertThat(error.message()).startsWith("ERR Protocol error"));
            assertThat(client.readReply()).isNull();
        }
    }

    @Test
    void handlesOneThousandConcurrentConnectionsOnSingleThread() throws Exception {
        int connectionCount = 1000;
        List<TestClient> clients = new ArrayList<>(connectionCount);
        try {
            // Mở đồng thời 1.000 kết nối giữ mở cùng lúc
            for (int i = 0; i < connectionCount; i++) {
                TestClient client = new TestClient(server.getPort());
                clients.add(client);
                assertThat(client.send("SET", "conn_" + i, "val_" + i)).isEqualTo(new SimpleString("OK"));
            }

            // Tất cả 1.000 kết nối vẫn đang mở và đọc lại được dữ liệu qua cùng 1 thread event loop
            for (int i = 0; i < connectionCount; i++) {
                assertThat(clients.get(i).send("GET", "conn_" + i)).isEqualTo(BulkString.of("val_" + i));
            }
            assertThat(store.size()).isEqualTo(connectionCount);
        } finally {
            for (TestClient client : clients) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Test
    void closeStopsServerAndReleasesPort() throws Exception {
        int port = server.getPort();
        server.close();
        serverThread.join();

        assertThat(serverThread.isAlive()).isFalse();
        assertThatThrownBy(() -> new Socket("localhost", port))
                .isInstanceOf(ConnectException.class);
    }

    private static class TestClient implements AutoCloseable {
        private final Socket socket;
        private final RespParser in;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(3000);
            in = new RespParser(new BufferedInputStream(socket.getInputStream()));
            out = socket.getOutputStream();
        }

        RespValue send(String... args) throws IOException {
            List<RespValue> elements = new ArrayList<>();
            for (String arg : args) {
                elements.add(BulkString.of(arg));
            }
            out.write(RespWriter.encode(new RespArray(elements)));
            out.flush();
            return readReply();
        }

        void sendRaw(String data) throws IOException {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        RespValue readReply() throws IOException {
            return in.read();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
