package com.redismini.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mô hình thread-per-client: mỗi kết nối được xử lý trên một thread riêng.
 */
public class BlockingServer implements AutoCloseable {

    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public BlockingServer(int port) {
        this.port = port;
    }

    /**
     * Mở cổng rồi chặn thread gọi hàm này, nhận client cho đến khi close().
     * Không mở được cổng (ví dụ đang bị chiếm) thì ném IOException ra ngoài:
     * server không có lý do gì để chạy tiếp.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("Listening on port " + getPort());

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
     * Phục vụ một client: đọc từng dòng, trả lời, lặp lại cho đến khi client ngắt kết nối.
     * Giai đoạn 2 sẽ thay việc đọc theo dòng bằng RespParser.
     */
    private void handle(Socket client) {
        SocketAddress address = client.getRemoteSocketAddress();
        System.out.println("Client connected: " + address
                + " on thread " + Thread.currentThread().getName());

        try (client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {

            String line;
            // readLine() chặn cho đến khi nhận đủ một dòng; trả về null khi client đóng kết nối
            while ((line = in.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                String reply = command.equalsIgnoreCase("PING")
                        ? "+PONG\r\n"
                        : "-ERR unknown command '" + command + "'\r\n";
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                // Đẩy dữ liệu đi ngay, không chờ đầy buffer
                out.flush();
            }
        } catch (IOException e) {
            // Client mất kết nối đột ngột (rút mạng, tắt ngang): chỉ ảnh hưởng client này
            System.err.println("Client error " + address + ": " + e.getMessage());
        }
        System.out.println("Client disconnected: " + address);
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
