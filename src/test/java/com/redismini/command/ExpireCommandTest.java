package com.redismini.command;

import com.redismini.TestClock;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpireCommandTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private final ExpireCommand expire = new ExpireCommand(store);

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void setsTtlOnExistingKeyAndReturnsOne() {
        store.set("k", bytes("v"));

        assertThat(expire.execute(List.of(BulkString.of("k"), BulkString.of("10"))))
                .isEqualTo(new RespInteger(1));

        assertThat(store.getTtlSeconds("k")).isEqualTo(10);

        clock.advanceSeconds(10);
        assertThat(store.get("k")).isNull();
    }

    @Test
    void returnsZeroForNonExistentKey() {
        assertThat(expire.execute(List.of(BulkString.of("missing"), BulkString.of("10"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void returnsZeroForAlreadyExpiredKey() {
        store.set("k", bytes("v"), 1_005_000L);
        clock.advanceSeconds(6);

        assertThat(expire.execute(List.of(BulkString.of("k"), BulkString.of("10"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void zeroOrNegativeSecondsDeletesKeyAndReturnsOne() {
        store.set("k1", bytes("v1"));
        store.set("k2", bytes("v2"));

        // seconds = 0 -> xoá key ngay, trả 1
        assertThat(expire.execute(List.of(BulkString.of("k1"), BulkString.of("0"))))
                .isEqualTo(new RespInteger(1));
        assertThat(store.exists("k1")).isFalse();

        // seconds < 0 -> xoá key ngay, trả 1
        assertThat(expire.execute(List.of(BulkString.of("k2"), BulkString.of("-5"))))
                .isEqualTo(new RespInteger(1));
        assertThat(store.exists("k2")).isFalse();

        // Key không tồn tại mà expire <= 0 -> trả 0
        assertThat(expire.execute(List.of(BulkString.of("k1"), BulkString.of("0"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void wrongNumberOfArgumentsReturnsError() {
        assertThat(expire.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'expire' command"));

        assertThat(expire.execute(List.of(BulkString.of("k"), BulkString.of("10"), BulkString.of("extra"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'expire' command"));
    }

    @Test
    void invalidIntegerSecondsReturnsError() {
        assertThat(expire.execute(List.of(BulkString.of("k"), BulkString.of("abc"))))
                .isEqualTo(new SimpleError("ERR value is not an integer or out of range"));

        assertThat(expire.execute(List.of(BulkString.of("k"), BulkString.of("10.5"))))
                .isEqualTo(new SimpleError("ERR value is not an integer or out of range"));
    }
}

