package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.protocol.RespWriter;
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
        server = new BlockingServer(0, CommandRegistry.createDefault());
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
            assertThat(client.send("PING")).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void pingIsCaseInsensitive() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("ping")).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void pingWithMessageEchoesIt() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING", "xin chào")).isEqualTo(BulkString.of("xin chào"));
        }
    }

    @Test
    void pingWithTooManyArgumentsIsAnError() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING", "a", "b"))
                    .isEqualTo(new SimpleError("ERR wrong number of arguments for 'ping' command"));
        }
    }

    @Test
    void repliesErrorToUnknownCommand() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("FOO", "x"))
                    .isEqualTo(new SimpleError("ERR unknown command 'FOO', with args beginning with: 'x' "));
        }
    }

    @Test
    void unknownCommandNameWithNewlinesDoesNotBreakReply() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("A\r\nB")).isInstanceOf(SimpleError.class);
            // Kết nối vẫn dùng được: câu trả lời trước không làm lệch luồng byte
            assertThat(client.send("PING")).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void servesManyCommandsOnSameConnection() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            assertThat(client.send("PING")).isEqualTo(new SimpleString("PONG"));
            assertThat(client.send("FOO")).isInstanceOf(SimpleError.class);
            assertThat(client.send("PING")).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void servesTwoClientsAtTheSameTime() throws IOException {
        try (TestClient first = new TestClient(server.getPort());
             TestClient second = new TestClient(server.getPort())) {
            assertThat(first.send("PING")).isEqualTo(new SimpleString("PONG"));
            // first vẫn đang mở kết nối: second vẫn phải được trả lời, không bị chặn
            assertThat(second.send("PING")).isEqualTo(new SimpleString("PONG"));
            assertThat(first.send("PING")).isEqualTo(new SimpleString("PONG"));
        }
    }

    @Test
    void acceptsInlineCommands() throws IOException {
        // Giống gõ tay qua telnet
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("PING\r\n");
            assertThat(client.readReply()).isEqualTo(new SimpleString("PONG"));
            client.sendRaw("ping hello\r\n");
            assertThat(client.readReply()).isEqualTo(BulkString.of("hello"));
        }
    }

    @Test
    void repliesToPipelinedCommandsInOrder() throws IOException {
        // Ba lệnh gửi một lượt, chưa chờ trả lời: server phải trả đủ 3 câu, đúng thứ tự
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nPING\r\n$1\r\na\r\nPING b\r\n");
            assertThat(client.readReply()).isEqualTo(new SimpleString("PONG"));
            assertThat(client.readReply()).isEqualTo(BulkString.of("a"));
            assertThat(client.readReply()).isEqualTo(BulkString.of("b"));
        }
    }

    @Test
    void handlesCommandSplitAcrossPackets() throws Exception {
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*2\r\n$4\r\nPI");
            // Chờ một chút để nửa đầu đi thành gói riêng: server đọc được nửa đầu rồi phải chờ tiếp
            Thread.sleep(100);
            client.sendRaw("NG\r\n$2\r\nhi\r\n");
            assertThat(client.readReply()).isEqualTo(BulkString.of("hi"));
        }
    }

    @Test
    void protocolErrorRepliesThenClosesConnection() throws IOException {
        try (TestClient client = new TestClient(server.getPort())) {
            client.sendRaw("*abc\r\n");
            assertThat(client.readReply()).isInstanceOfSatisfying(SimpleError.class,
                    error -> assertThat(error.message()).startsWith("ERR Protocol error"));
            // Server đã đóng kết nối: đọc tiếp gặp hết stream
            assertThat(client.readReply()).isNull();
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

    /** Client tối giản: gửi lệnh dạng RESP như redis-cli, đọc câu trả lời bằng RespParser. */
    private static class TestClient implements AutoCloseable {
        private final Socket socket;
        private final RespParser in;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            // Đọc socket không phản ứng với interrupt, nên @Timeout không cắt được read().
            // SoTimeout làm read() tự ném SocketTimeoutException nếu chờ quá 2 giây.
            socket.setSoTimeout(2000);
            in = new RespParser(new BufferedInputStream(socket.getInputStream()));
            out = socket.getOutputStream();
        }

        /** Gửi một lệnh dạng mảng Bulk String rồi chờ câu trả lời. */
        RespValue send(String... args) throws IOException {
            List<RespValue> elements = new ArrayList<>();
            for (String arg : args) {
                elements.add(BulkString.of(arg));
            }
            out.write(RespWriter.encode(new RespArray(elements)));
            out.flush();
            return readReply();
        }

        /** Gửi nguyên văn, không chờ trả lời: dùng cho inline, pipeline, dữ liệu hỏng. */
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
