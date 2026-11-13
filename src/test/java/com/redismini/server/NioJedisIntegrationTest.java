package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.persistence.AofLoader;
import com.redismini.persistence.AofWriter;
import com.redismini.persistence.FsyncPolicy;
import com.redismini.store.DataStore;
import com.redismini.store.ExpiryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm thử tích hợp Giai đoạn 6: chạy Jedis thật qua {@link NioServer} đơn luồng
 * kết hợp TTL, active eviction trên event loop và AOF.
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class NioJedisIntegrationTest {

    @TempDir
    Path tempDir;

    private Path aofFile;
    private DataStore store;
    private AofWriter aofWriter;
    private ExpiryManager expiryManager;
    private NioServer server;
    private Thread serverThread;
    private Jedis jedis;

    @BeforeEach
    void setUp() throws IOException {
        aofFile = tempDir.resolve("nio.aof");
        store = new DataStore();
        aofWriter = new AofWriter(aofFile, FsyncPolicy.ALWAYS);
        aofWriter.start();
        expiryManager = new ExpiryManager(store);
        server = new NioServer(0, CommandRegistry.createDefault(store, aofWriter), expiryManager);
        server.bind();
        serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "nio-event-loop");
        serverThread.start();

        jedis = new Jedis("localhost", server.getPort());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (jedis != null) {
            jedis.close();
        }
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join();
        }
        if (aofWriter != null) {
            aofWriter.close();
        }
    }

    @Test
    void supportsFullCommandSetOnNioEventLoop() throws Exception {
        assertThat(jedis.ping()).isEqualTo("PONG");
        assertThat(jedis.echo("nio")).isEqualTo("nio");

        assertThat(jedis.set("name", "Chien")).isEqualTo("OK");
        assertThat(jedis.get("name")).isEqualTo("Chien");
        assertThat(jedis.incr("visits")).isEqualTo(1);
        assertThat(jedis.incr("visits")).isEqualTo(2);
        assertThat(jedis.exists("name", "visits", "missing")).isEqualTo(2);

        assertThat(jedis.set("temp", "1", new SetParams().ex(20))).isEqualTo("OK");
        assertThat(jedis.ttl("temp")).isBetween(1L, 20L);
        assertThat(jedis.persist("temp")).isEqualTo(1);
        assertThat(jedis.ttl("temp")).isEqualTo(-1);

        assertThat(jedis.bgrewriteaof()).isEqualTo("Background append only file rewriting started");
        aofWriter.getRewriteFuture().get(5, TimeUnit.SECONDS);

        DataStore reloaded = new DataStore();
        assertThat(AofLoader.load(aofFile, CommandRegistry.createDefault(reloaded))).isEqualTo(3);
        assertThat(reloaded.get("name")).isEqualTo("Chien".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void activeEvictionRunsInsideNioEventLoop() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            jedis.set("evict_" + i, "val", new SetParams().ex(1));
        }
        assertThat(store.size()).isEqualTo(5);

        // Không cần client gọi GET, vòng lặp event loop tự dọn các key hết hạn
        Thread.sleep(1300);
        assertThat(store.size()).isZero();
    }
}
