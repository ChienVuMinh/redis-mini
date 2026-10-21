package com.redismini.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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

    /** Bước 1.1: chỉ ghi nhận kết nối rồi đóng. Bước 1.2 sẽ đọc PING ở đây. */
    private void handle(Socket client) {
        try (client) {
            System.out.println("Client connected: " + client.getRemoteSocketAddress()
                    + " on thread " + Thread.currentThread().getName());
        } catch (IOException e) {
            // Lỗi của một client không được làm sập cả server
            System.err.println("Client error: " + e.getMessage());
        }
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
