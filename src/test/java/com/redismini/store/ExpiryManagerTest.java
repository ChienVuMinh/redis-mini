package com.redismini.store;

import com.redismini.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryManagerTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private ExpiryManager expiryManager;

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        expiryManager = new ExpiryManager(store);
    }

    @AfterEach
    void tearDown() {
        if (expiryManager != null) {
            expiryManager.close();
        }
    }

    @Test
    void doesNothingWhenNoKeysWithTtl() {
        store.set("perm1", bytes("v1"));
        store.set("perm2", bytes("v2"));

        int evicted = expiryManager.runEvictionCycle();
        assertThat(evicted).isZero();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void evictsExpiredKeysInCycle() {
        // Tạo 10 key còn hạn (epoch 1_010_000) và 10 key hết hạn (epoch 1_002_000)
        for (int i = 0; i < 10; i++) {
            store.set("alive_" + i, bytes("v"), 1_010_000L);
            store.set("dead_" + i, bytes("v"), 1_002_000L);
        }

        // Tua qua thời điểm hết hạn
        clock.advanceSeconds(5);

        int evicted = expiryManager.runEvictionCycle();
        assertThat(evicted).isEqualTo(10);
        assertThat(store.size()).isEqualTo(10);

        for (int i = 0; i < 10; i++) {
            assertThat(store.exists("alive_" + i)).isTrue();
            assertThat(store.exists("dead_" + i)).isFalse();
        }
    }

    @Test
    void reloopsWhenMoreThanQuarterAreExpired() {
        // Tạo 40 key, tất cả đều đã hết hạn
        for (int i = 0; i < 40; i++) {
            store.set("dead_" + i, bytes("v"), 1_002_000L);
        }

        clock.advanceSeconds(5);

        // Chu kỳ sẽ lặp lại (> 25% hết hạn ở mẫu đầu 20 key) và quét tiếp mẫu thứ 2
        int evicted = expiryManager.runEvictionCycle();
        assertThat(evicted).isEqualTo(40);
        assertThat(store.size()).isZero();
    }

    @Test
    void stopsWhenThresholdNotExceeded() {
        // Tạo 20 key có TTL: 18 key sống, 2 key chết (10% <= 25%)
        for (int i = 0; i < 18; i++) {
            store.set("alive_" + i, bytes("v"), 1_010_000L);
        }
        store.set("dead_1", bytes("v"), 1_002_000L);
        store.set("dead_2", bytes("v"), 1_002_000L);

        clock.advanceSeconds(5);

        int evicted = expiryManager.runEvictionCycle();
        assertThat(evicted).isEqualTo(2);
        assertThat(store.size()).isEqualTo(18);
    }

    @Test
    void startAndCloseLifecycle() {
        expiryManager.start();
        assertThat(expiryManager.isRunning()).isTrue();

        expiryManager.close();
        assertThat(expiryManager.isRunning()).isFalse();
    }
}

