package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.persistence.AofLoader;
import com.redismini.persistence.AofWriter;
import com.redismini.persistence.FsyncPolicy;
import com.redismini.store.DataStore;
import com.redismini.store.ExpiryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm thử tích hợp Giai đoạn 5: ghi dữ liệu qua Jedis với AOF bật, tắt server,
 * khởi động lại server mới từ file AOF và xác nhận toàn bộ dữ liệu được khôi phục.
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AofIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndRestoresDataAcrossServerRestartsWithJedis() throws Exception {
        Path aofFile = tempDir.resolve("appendonly.aof");

        // Lần chạy 1: ghi dữ liệu bằng Jedis
        DataStore store1 = new DataStore();
        try (AofWriter aof1 = new AofWriter(aofFile, FsyncPolicy.ALWAYS);
             ExpiryManager expiry1 = new ExpiryManager(store1);
             BlockingServer server1 = new BlockingServer(0, CommandRegistry.createDefault(store1, aof1), expiry1)) {
            aof1.start();
            server1.bind();
            Thread t1 = startServeThread(server1);

            try (Jedis jedis = new Jedis("localhost", server1.getPort())) {
                jedis.set("user", "Chien");
                jedis.incr("counter");
                jedis.incr("counter");
                jedis.set("ttl_key", "alive", new SetParams().ex(30));
                jedis.set("deleted", "bye");
                jedis.del("deleted");
            }
            server1.close();
            t1.join();
        }

        // Giả lập crash làm hỏng đuôi file AOF ngay sau đó
        Files.write(aofFile, "*3\r\n$3\r\nSET\r\n$3\r\nbad\r\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        // Lần chạy 2: khôi phục từ AOF rồi phục vụ client mới
        DataStore store2 = new DataStore();
        int replayed = AofLoader.load(aofFile, CommandRegistry.createDefault(store2));
        assertThat(replayed).isEqualTo(6);

        try (AofWriter aof2 = new AofWriter(aofFile, FsyncPolicy.ALWAYS);
             ExpiryManager expiry2 = new ExpiryManager(store2);
             BlockingServer server2 = new BlockingServer(0, CommandRegistry.createDefault(store2, aof2), expiry2)) {
            aof2.start();
            server2.bind();
            Thread t2 = startServeThread(server2);

            try (Jedis jedis = new Jedis("localhost", server2.getPort())) {
                assertThat(jedis.get("user")).isEqualTo("Chien");
                assertThat(jedis.get("counter")).isEqualTo("2");
                assertThat(jedis.get("ttl_key")).isEqualTo("alive");
                assertThat(jedis.ttl("ttl_key")).isBetween(1L, 30L);
                assertThat(jedis.get("deleted")).isNull();
                assertThat(jedis.get("bad")).isNull();

                // Tiếp tục ghi mới sau khi hồi phục
                assertThat(jedis.incr("counter")).isEqualTo(3);
            }
            server2.close();
            t2.join();
        }
    }

    @Test
    void bgRewriteAofCompactsFileAndRestoresStateViaJedis() throws Exception {
        Path aofFile = tempDir.resolve("rewrite.aof");

        DataStore store1 = new DataStore();
        try (AofWriter aof1 = new AofWriter(aofFile, FsyncPolicy.ALWAYS);
             ExpiryManager expiry1 = new ExpiryManager(store1);
             BlockingServer server1 = new BlockingServer(0, CommandRegistry.createDefault(store1, aof1), expiry1)) {
            aof1.start();
            server1.bind();
            Thread t1 = startServeThread(server1);

            try (Jedis jedis = new Jedis("localhost", server1.getPort())) {
                for (int i = 0; i < 30; i++) {
                    jedis.set("hot_key", "v_" + i);
                }
                jedis.set("ttl_key", "alive", new SetParams().ex(30));

                long sizeBefore = Files.size(aofFile);
                String reply = jedis.bgrewriteaof();
                assertThat(reply).isEqualTo("Background append only file rewriting started");

                aof1.getRewriteFuture().get(5, TimeUnit.SECONDS);
                long sizeAfter = Files.size(aofFile);
                assertThat(sizeAfter).isLessThan(sizeBefore);

                // Ghi thêm sau khi rewrite xong
                jedis.set("after_rewrite", "done");
            }
            server1.close();
            t1.join();
        }

        // Restart server mới và đọc lại từ file AOF đã thu gọn
        DataStore store2 = new DataStore();
        int replayed = AofLoader.load(aofFile, CommandRegistry.createDefault(store2));
        assertThat(replayed).isEqualTo(3);

        try (BlockingServer server2 = new BlockingServer(0, CommandRegistry.createDefault(store2))) {
            server2.bind();
            Thread t2 = startServeThread(server2);

            try (Jedis jedis = new Jedis("localhost", server2.getPort())) {
                assertThat(jedis.get("hot_key")).isEqualTo("v_29");
                assertThat(jedis.get("ttl_key")).isEqualTo("alive");
                assertThat(jedis.ttl("ttl_key")).isBetween(1L, 30L);
                assertThat(jedis.get("after_rewrite")).isEqualTo("done");
            }
            server2.close();
            t2.join();
        }
    }

    private static Thread startServeThread(BlockingServer server) {
        Thread thread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        thread.start();
        return thread;
    }
}

