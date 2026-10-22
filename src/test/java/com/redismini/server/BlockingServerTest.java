package com.redismini.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Khởi động server thật trên một cổng trống, kết nối bằng Socket thật.
 * @Timeout chạy test trên thread riêng: treo quá 5 giây (kể cả kẹt ở I/O) thì test hỏng
 * thay vì chờ mãi.
 */
@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BlockingServerTest {

    private BlockingServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        // Port 0: OS tự chọn cổng trống, các test không bao giờ đụng cổng nhau hay đụng 6380
        server = new BlockingServer(0);
        // bind() chạy trên thread của test: xong dòng này là cổng chắc chắn đã mở
        server.bind();
        // serve() chặn, nên chạy trên thread riêng
        serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        serverThread.join();
    }

    @Test
    void repliesPongToPing() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING")).isEqualTo("+PONG");
        }
    }

    @Test
    void pingIsCaseInsensitive() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("ping")).isEqualTo("+PONG");
        }
    }

    @Test
    void repliesErrorToUnknownCommand() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("HELLO")).isEqualTo("-ERR unknown command 'HELLO'");
        }
    }

    @Test
    void servesManyCommandsOnSameConnection() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING")).isEqualTo("+PONG");
            assertThat(client.send("HELLO")).startsWith("-ERR");
            assertThat(client.send("PING")).isEqualTo("+PONG");
        }
    }

    @Test
    void servesTwoClientsAtTheSameTime() throws IOException {
        try (TestClient first = new TestClient(server.getPort());
             TestClient second = new TestClient(server.getPort())) {
            assertThat(first.send("PING")).isEqualTo("+PONG");
            // first vẫn đang mở kết nối: second vẫn phải được trả lời, không bị chặn
            assertThat(second.send("PING")).isEqualTo("+PONG");
            assertThat(first.send("PING")).isEqualTo("+PONG");
        }
    }

    @Test
    void closeStopsServerAndReleasesPort() throws Exception {
        int port = server.getPort();
        server.close();
        // serve() phải thoát êm, không treo
        serverThread.join();

        assertThat(serverThread.isAlive()).isFalse();
        assertThatThrownBy(() -> new Socket("localhost", port))
                .isInstanceOf(ConnectException.class);
    }

    /** Client tối giản: gửi một dòng, đọc một dòng trả lời. */
    private static class TestClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            // Đọc socket không phản ứng với interrupt, nên @Timeout không cắt được readLine().
            // SoTimeout làm readLine() tự ném SocketTimeoutException nếu chờ quá 2 giây.
            socket.setSoTimeout(2000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        String send(String command) throws IOException {
            out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
