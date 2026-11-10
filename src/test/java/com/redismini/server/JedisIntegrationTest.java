package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.store.DataStore;
import com.redismini.store.ExpiryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm thử tích hợp giai đoạn 3 & 4: dùng Jedis client thật kết nối vào BlockingServer
 * để gửi lệnh và xác nhận tương thích hoàn toàn với giao thức Redis.
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JedisIntegrationTest {

    private DataStore store;
    private ExpiryManager expiryManager;
    private BlockingServer server;
    private Thread serverThread;
    private Jedis jedis;

    @BeforeEach
    void setUp() throws IOException {
        store = new DataStore();
        expiryManager = new ExpiryManager(store);
        server = new BlockingServer(0, CommandRegistry.createDefault(store), expiryManager);
        server.bind();
        serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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
    }

    @Test
    void pingWithoutMessageReturnsPong() {
        assertThat(jedis.ping()).isEqualTo("PONG");
    }

    @Test
    void pingWithMessageReturnsMessage() {
        assertThat(jedis.ping("xin chao")).isEqualTo("xin chao");
    }

    @Test
    void echoReturnsMessage() {
        assertThat(jedis.echo("redis mini test")).isEqualTo("redis mini test");
    }

    @Test
    void setAndGetWorkProperly() {
        assertThat(jedis.set("name", "Chien")).isEqualTo("OK");
        assertThat(jedis.get("name")).isEqualTo("Chien");
        assertThat(jedis.get("not_found")).isNull();
    }

    @Test
    void existsReturnsCorrectCount() {
        jedis.set("k1", "v1");
        jedis.set("k2", "v2");

        assertThat(jedis.exists("k1")).isTrue();
        assertThat(jedis.exists("k3")).isFalse();
        assertThat(jedis.exists("k1", "k2", "k3")).isEqualTo(2);
    }

    @Test
    void delRemovesKeysAndReturnsCount() {
        jedis.set("k1", "v1");
        jedis.set("k2", "v2");

        assertThat(jedis.del("k1", "k3")).isEqualTo(1);
        assertThat(jedis.get("k1")).isNull();
        assertThat(jedis.get("k2")).isEqualTo("v2");
    }

    @Test
    void incrIncrementsNumberAndHandlesErrors() {
        assertThat(jedis.incr("counter")).isEqualTo(1);
        assertThat(jedis.incr("counter")).isEqualTo(2);

        jedis.set("text", "abc");
        assertThatThrownBy(() -> jedis.incr("text"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("ERR value is not an integer or out of range");
    }

    @Test
    void multipleClientsShareData() {
        try (Jedis secondClient = new Jedis("localhost", server.getPort())) {
            jedis.set("shared", "100");
            assertThat(secondClient.get("shared")).isEqualTo("100");

            secondClient.incr("shared");
            assertThat(jedis.get("shared")).isEqualTo("101");
        }
    }

    @Test
    void setWithExOptionExpiresKey() throws InterruptedException {
        String reply = jedis.set("temp", "val", new SetParams().ex(1));
        assertThat(reply).isEqualTo("OK");
        assertThat(jedis.get("temp")).isEqualTo("val");
        assertThat(jedis.ttl("temp")).isGreaterThan(0);

        // Đợi hơn 1 giây để key hết hạn
        Thread.sleep(1100);

        assertThat(jedis.get("temp")).isNull();
        assertThat(jedis.ttl("temp")).isEqualTo(-2);
    }

    @Test
    void expireAndPersistCommandsWorkProperly() {
        jedis.set("k", "v");
        assertThat(jedis.ttl("k")).isEqualTo(-1);

        // Đặt TTL 10 giây
        long expireResult = jedis.expire("k", 10);
        assertThat(expireResult).isEqualTo(1);
        assertThat(jedis.ttl("k")).isGreaterThan(0);

        // PERSIST xoá TTL, trả về 1
        long persistResult = jedis.persist("k");
        assertThat(persistResult).isEqualTo(1);
        assertThat(jedis.ttl("k")).isEqualTo(-1);

        // PERSIST lại lần nữa trả về 0
        assertThat(jedis.persist("k")).isEqualTo(0);
    }

    @Test
    void expireKeyZeroOrNegativeDeletesKey() {
        jedis.set("k1", "v1");
        jedis.set("k2", "v2");

        assertThat(jedis.expire("k1", 0)).isEqualTo(1);
        assertThat(jedis.exists("k1")).isFalse();

        assertThat(jedis.expire("k2", -5)).isEqualTo(1);
        assertThat(jedis.exists("k2")).isFalse();

        // Key không tồn tại
        assertThat(jedis.expire("missing", 10)).isEqualTo(0);
    }

    @Test
    void activeEvictionCleansExpiredKeysInBackground() throws InterruptedException {
        // Tạo 5 key có TTL 1 giây
        for (int i = 0; i < 5; i++) {
            jedis.set("auto_" + i, "val", new SetParams().ex(1));
        }
        assertThat(store.size()).isEqualTo(5);

        // Đợi 1.3s để key hết hạn và ExpiryManager chạy ít nhất 1 chu kỳ dọn dẹp
        Thread.sleep(1300);

        // Dữ liệu trong RAM đã được giải phóng chủ động mà không cần client gọi GET
        assertThat(store.size()).isZero();
    }
}

