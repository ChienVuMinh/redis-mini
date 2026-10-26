package com.redismini.server;

import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespProtocolException;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.protocol.RespWriter;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mô hình thread-per-client: mỗi kết nối được xử lý trên một thread riêng.
 */
public class BlockingServer implements AutoCloseable {

    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public BlockingServer(int port) {
        this.port = port;
    }

    /** Mở cổng rồi phục vụ client cho đến khi close(). Chặn thread gọi hàm này. */
    public void start() throws IOException {
        bind();
        serve();
    }

    /**
     * Mở cổng. Không mở được (ví dụ cổng đang bị chiếm) thì ném IOException ra ngoài:
     * server không có lý do gì để chạy tiếp.
     * Tách riêng khỏi serve() để test biết chắc cổng đã mở trước khi kết nối.
     */
    public void bind() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("Listening on port " + getPort());
    }

    /** Vòng lặp nhận client. Chặn thread gọi hàm này cho đến khi close(). */
    public void serve() throws IOException {
        while (running) {
            Socket client;
            try {
                // Chặn ở đây cho đến khi có client kết nối
                client = serverSocket.accept();
            } catch (SocketException e) {
                // close() đóng serverSocket làm accept() ném lỗi: thoát vòng lặp bình thường
                if (!running) {
                    break;
                }
                throw e;
            }
            executor.submit(() -> handle(client));
        }
    }

    /**
     * Phục vụ một client: đọc một lệnh, trả lời, lặp lại cho đến khi client ngắt kết nối.
     */
    private void handle(Socket client) {
        SocketAddress address = client.getRemoteSocketAddress();
        System.out.println("Client connected: " + address
                + " on thread " + Thread.currentThread().getName());

        try (client;
             // Parser đọc từng byte: có buffer thì mỗi lần gọi xuống OS lấy được cả khối
             InputStream in = new BufferedInputStream(client.getInputStream());
             OutputStream out = new BufferedOutputStream(client.getOutputStream())) {

            RespParser parser = new RespParser(in);
            RespWriter writer = new RespWriter(out);
            try {
                List<BulkString> command;
                // readCommand() chặn cho đến khi đủ một lệnh; trả về null khi client đóng kết nối
                while ((command = parser.readCommand()) != null) {
                    writer.write(execute(command));
                    // Đẩy câu trả lời đi ngay, không chờ đầy buffer
                    writer.flush();
                }
            } catch (RespProtocolException e) {
                // Không còn biết lệnh tiếp theo bắt đầu ở byte nào: báo lỗi rồi đóng kết nối, như Redis thật
                writer.write(new SimpleError("ERR Protocol error: " + e.getMessage()));
                writer.flush();
            }
        } catch (IOException e) {
            // Client mất kết nối đột ngột (rút mạng, tắt ngang): chỉ ảnh hưởng client này
            System.err.println("Client error " + address + ": " + e.getMessage());
        }
        System.out.println("Client disconnected: " + address);
    }

    /** Chạy một lệnh. Giai đoạn 3 sẽ thay bằng CommandRegistry. */
    private RespValue execute(List<BulkString> command) {
        String name = command.get(0).asString();
        if (name.equalsIgnoreCase("PING")) {
            return switch (command.size()) {
                case 1 -> new SimpleString("PONG");
                // PING message: trả lại đúng message, dạng Bulk String như Redis thật
                case 2 -> command.get(1);
                default -> new SimpleError("ERR wrong number of arguments for 'ping' command");
            };
        }
        StringBuilder args = new StringBuilder();
        for (BulkString arg : command.subList(1, command.size())) {
            args.append('\'').append(arg.asString()).append("' ");
        }
        // Tên lệnh do client gửi có thể chứa \r\n, mà Simple Error thì không được chứa
        String message = "ERR unknown command '" + name + "', with args beginning with: " + args;
        return new SimpleError(message.replace('\r', ' ').replace('\n', ' '));
    }

    /** Cổng thật đang lắng nghe. Khi tạo server với port 0, OS sẽ tự chọn cổng trống. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
        executor.shutdownNow();
    }
}
