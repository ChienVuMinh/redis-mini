package com.redismini.server;

import com.redismini.command.CommandRegistry;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm thử tích hợp giai đoạn 3: dùng Jedis client thật kết nối vào BlockingServer
 * để gửi lệnh và xác nhận tương thích hoàn toàn với giao thức Redis.
 */
@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JedisIntegrationTest {

    private BlockingServer server;
    private Thread serverThread;
    private Jedis jedis;

    @BeforeEach
    void setUp() throws IOException {
        server = new BlockingServer(0, CommandRegistry.createDefault(new DataStore()));
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
}

