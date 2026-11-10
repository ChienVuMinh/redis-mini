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

class TtlCommandTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private final TtlCommand ttl = new TtlCommand(store);

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void returnsMinusTwoWhenKeyDoesNotExist() {
        assertThat(ttl.execute(List.of(BulkString.of("missing"))))
                .isEqualTo(new RespInteger(-2));
    }

    @Test
    void returnsMinusTwoWhenKeyIsExpired() {
        store.set("k", bytes("v"), 1_005_000L);
        clock.advanceSeconds(6);

        assertThat(ttl.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(-2));
    }

    @Test
    void returnsMinusOneWhenKeyHasNoTtl() {
        store.set("k", bytes("v"));

        assertThat(ttl.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(-1));
    }

    @Test
    void returnsRemainingSecondsWhenKeyHasTtl() {
        // Hết hạn sau 10 giây (epoch 1_010_000)
        store.set("k", bytes("v"), 1_010_000L);

        assertThat(ttl.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(10));

        clock.advanceSeconds(3);
        assertThat(ttl.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(7));
    }

    @Test
    void wrongNumberOfArgumentsReturnsError() {
        assertThat(ttl.execute(List.of()))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'ttl' command"));

        assertThat(ttl.execute(List.of(BulkString.of("k1"), BulkString.of("k2"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'ttl' command"));
    }
}

