package com.redismini;

import com.redismini.command.CommandRegistry;
import com.redismini.persistence.AofLoader;
import com.redismini.persistence.AofWriter;
import com.redismini.persistence.FsyncPolicy;
import com.redismini.server.BlockingServer;
import com.redismini.store.DataStore;
import com.redismini.store.ExpiryManager;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Điểm khởi động: java -jar redis-mini.jar --port 6380
 */
public class Main {

    static final int DEFAULT_PORT = 6380;
    static final Path DEFAULT_AOF_PATH = Path.of("appendonly.aof");

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        System.out.println("redis-mini starting on port " + port);

        DataStore store = new DataStore();
        // Load và dọn đuôi file AOF (nếu bị cắt dở) trước khi mở AofWriter để tránh xung đột file handle trên Windows
        int replayed = AofLoader.load(DEFAULT_AOF_PATH, CommandRegistry.createDefault(store));
        if (replayed > 0) {
            System.out.println("Loaded " + replayed + " commands from " + DEFAULT_AOF_PATH);
        }

        ExpiryManager expiryManager = new ExpiryManager(store);
        try (AofWriter aofWriter = new AofWriter(DEFAULT_AOF_PATH, FsyncPolicy.EVERYSEC)) {
            aofWriter.start();
            CommandRegistry registry = CommandRegistry.createDefault(store, aofWriter);
            try (BlockingServer server = new BlockingServer(port, registry, expiryManager)) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.close();
                        aofWriter.close();
                    } catch (IOException ignored) {
                    }
                }, "shutdown-hook"));
                server.start();
            }
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
