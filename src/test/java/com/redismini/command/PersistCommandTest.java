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

class PersistCommandTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private final PersistCommand persist = new PersistCommand(store);

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void removesTtlAndReturnsOne() {
        store.set("k", bytes("v"), 1_010_000L);
        assertThat(store.getTtlSeconds("k")).isGreaterThan(0);

        assertThat(persist.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(1));

        assertThat(store.getTtlSeconds("k")).isEqualTo(-1);

        // Key không còn bị hết hạn nữa
        clock.advanceSeconds(15);
        assertThat(store.get("k")).isEqualTo(bytes("v"));
    }

    @Test
    void returnsZeroWhenKeyHasNoTtl() {
        store.set("k", bytes("v"));

        assertThat(persist.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void returnsZeroWhenKeyDoesNotExist() {
        assertThat(persist.execute(List.of(BulkString.of("missing"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void returnsZeroWhenKeyIsAlreadyExpired() {
        store.set("k", bytes("v"), 1_005_000L);
        clock.advanceSeconds(6);

        assertThat(persist.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void wrongNumberOfArgumentsReturnsError() {
        assertThat(persist.execute(List.of()))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'persist' command"));

        assertThat(persist.execute(List.of(BulkString.of("k1"), BulkString.of("k2"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'persist' command"));
    }
}

