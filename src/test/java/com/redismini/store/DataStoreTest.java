package com.redismini.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataStoreTest {

    private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000_000));
    private final DataStore store = new DataStore(clock);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertThat(store.get("nope")).isNull();
    }

    @Test
    void setThenGet() {
        store.set("name", bytes("chiến"));

        assertThat(store.get("name")).isEqualTo(bytes("chiến"));
    }

    @Test
    void setOverwritesExistingValue() {
        store.set("k", bytes("1"));
        store.set("k", bytes("2"));

        assertThat(store.get("k")).isEqualTo(bytes("2"));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void storesBinaryValuesAsIs() {
        // Value là byte bất kỳ, kể cả byte 0 và \r\n
        byte[] binary = {0, (byte) 0xFF, '\r', '\n', 42};
        store.set("bin", binary);

        assertThat(store.get("bin")).isEqualTo(binary);
    }

    @Test
    void deleteRemovesKeyAndReportsWhetherItExisted() {
        store.set("k", bytes("v"));

        assertThat(store.delete("k")).isTrue();
        assertThat(store.delete("k")).isFalse();
        assertThat(store.get("k")).isNull();
    }

    @Test
    void existsAndSize() {
        assertThat(store.exists("a")).isFalse();
        assertThat(store.size()).isZero();

        store.set("a", bytes("1"));
        store.set("b", bytes("2"));

        assertThat(store.exists("a")).isTrue();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void updateReceivesCurrentValueAndStoresResult() {
        store.set("k", bytes("a"));

        byte[] result = store.update("k", old -> bytes(new String(old, StandardCharsets.UTF_8) + "b"));

        assertThat(result).isEqualTo(bytes("ab"));
        assertThat(store.get("k")).isEqualTo(bytes("ab"));
    }

    @Test
    void updateOnMissingKeyReceivesNull() {
        store.update("k", old -> {
            assertThat(old).isNull();
            return bytes("new");
        });

        assertThat(store.get("k")).isEqualTo(bytes("new"));
    }

    @Test
    void updateReturningNullDeletesKey() {
        store.set("k", bytes("v"));

        assertThat(store.update("k", old -> null)).isNull();
        assertThat(store.exists("k")).isFalse();
    }

    @Test
    void updateThatThrowsChangesNothing() {
        store.set("k", bytes("v"));

        assertThatThrownBy(() -> store.update("k", old -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.get("k")).isEqualTo(bytes("v"));
    }

    @Test
    void emptyValueIsDifferentFromMissingKey() {
        // SET k "" là hợp lệ: key tồn tại, value rỗng. Khác với key không tồn tại (nil)
        store.set("k", bytes(""));

        assertThat(store.exists("k")).isTrue();
        assertThat(store.get("k")).isEmpty();
    }

    @Test
    void getExpiredKeyReturnsNullAndLazyEvicts() {
        // Hết hạn sau 5 giây (epoch 1_005_000)
        store.set("temp", bytes("val"), 1_005_000L);

        // Chưa hết hạn
        clock.advance(Duration.ofSeconds(4));
        assertThat(store.get("temp")).isEqualTo(bytes("val"));

        // Vừa đúng lúc hết hạn
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.get("temp")).isNull();
        // Lazy eviction đã xoá khỏi map nội bộ
        assertThat(store.size()).isZero();
    }

    @Test
    void existsOnExpiredKeyReturnsFalseAndLazyEvicts() {
        store.set("temp", bytes("val"), 1_005_000L);
        clock.advance(Duration.ofSeconds(5));

        assertThat(store.exists("temp")).isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    void deleteOnExpiredKeyReturnsFalse() {
        store.set("temp", bytes("val"), 1_005_000L);
        clock.advance(Duration.ofSeconds(5));

        // Key đã hết hạn nên DEL trả về 0 (false)
        assertThat(store.delete("temp")).isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    void setWithoutTtlOverwritesAndClearsExistingTtl() {
        store.set("k", bytes("val"), 1_005_000L);
        assertThat(store.getTtlSeconds("k")).isGreaterThan(0);

        // SET lại không có TTL -> trở thành vĩnh viễn
        store.set("k", bytes("new_val"));
        assertThat(store.getTtlSeconds("k")).isEqualTo(-1);

        // Qua thời điểm cũ, key vẫn tồn tại
        clock.advance(Duration.ofSeconds(10));
        assertThat(store.get("k")).isEqualTo(bytes("new_val"));
    }

    @Test
    void updateOnExpiredKeyTreatsOldValueAsNullAndClearsTtl() {
        store.set("counter", bytes("41"), 1_005_000L);
        clock.advance(Duration.ofSeconds(6));

        // Update khi đã hết hạn: old coi như null, tạo giá trị mới không có TTL
        byte[] result = store.update("counter", old -> {
            assertThat(old).isNull();
            return bytes("1");
        });

        assertThat(result).isEqualTo(bytes("1"));
        assertThat(store.get("counter")).isEqualTo(bytes("1"));
        assertThat(store.getTtlSeconds("counter")).isEqualTo(-1);
    }

    @Test
    void setExpireWorksCorrectly() {
        store.set("k", bytes("v"));
        assertThat(store.getTtlSeconds("k")).isEqualTo(-1);

        // Đặt hết hạn sau 10 giây (epoch 1_010_000)
        boolean ok = store.setExpire("k", 1_010_000L);
        assertThat(ok).isTrue();
        assertThat(store.getTtlSeconds("k")).isEqualTo(10);

        // Key không tồn tại thì không đặt được expire
        assertThat(store.setExpire("missing", 1_010_000L)).isFalse();
    }

    @Test
    void getTtlSecondsReturnsMinusTwoForMissingOrExpired() {
        assertThat(store.getTtlSeconds("missing")).isEqualTo(-2);

        store.set("k", bytes("v"), 1_002_000L);
        clock.advance(Duration.ofSeconds(3));
        assertThat(store.getTtlSeconds("k")).isEqualTo(-2);
    }

    @Test
    void persistRemovesTtl() {
        store.set("k", bytes("v"), 1_010_000L);
        assertThat(store.getTtlSeconds("k")).isGreaterThan(0);

        assertThat(store.persist("k")).isTrue();
        assertThat(store.getTtlSeconds("k")).isEqualTo(-1);

        // PERSIST lại lần 2 hoặc trên key không có TTL trả false
        assertThat(store.persist("k")).isFalse();
        assertThat(store.persist("missing")).isFalse();
    }

    @Test
    void evictIfExpiredDeletesOnlyExpiredKeys() {
        store.set("alive", bytes("1"), 1_010_000L);
        store.set("dead", bytes("2"), 1_002_000L);
        store.set("permanent", bytes("3"));

        clock.advance(Duration.ofSeconds(3));

        assertThat(store.evictIfExpired("alive")).isFalse();
        assertThat(store.evictIfExpired("dead")).isTrue();
        assertThat(store.evictIfExpired("permanent")).isFalse();

        assertThat(store.exists("alive")).isTrue();
        assertThat(store.exists("dead")).isFalse();
        assertThat(store.exists("permanent")).isTrue();
    }

    /** Đồng hồ giả lập có thể chủ động tua thời gian mà không cần Thread.sleep */
    private static class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone = ZoneId.of("UTC");

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

