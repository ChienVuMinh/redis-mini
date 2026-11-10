package com.redismini;

import com.redismini.command.CommandRegistry;
import com.redismini.server.BlockingServer;
import com.redismini.store.DataStore;
import com.redismini.store.ExpiryManager;

import java.io.IOException;

/**
 * Điểm khởi động: java -jar redis-mini.jar --port 6380
 */
public class Main {

    static final int DEFAULT_PORT = 6380;

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        System.out.println("redis-mini starting on port " + port);
        DataStore store = new DataStore();
        ExpiryManager expiryManager = new ExpiryManager(store);
        try (BlockingServer server = new BlockingServer(port, CommandRegistry.createDefault(store), expiryManager)) {
            server.start();
        }
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
