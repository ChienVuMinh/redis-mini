package com.redismini;

/**
 * Điểm khởi động: java -jar redis-mini.jar --port 6380
 */
public class Main {

    static final int DEFAULT_PORT = 6380;

    public static void main(String[] args) {
        int port = parsePort(args);
        System.out.println("redis-mini starting on port " + port);
        // TODO Giai đoạn 1: khởi động BlockingServer tại đây
    }

    static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return DEFAULT_PORT;
    }
}
