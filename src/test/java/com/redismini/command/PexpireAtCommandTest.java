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

class PexpireAtCommandTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private final PexpireAtCommand pexpireAt = new PexpireAtCommand(store);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void setsAbsoluteExpirationTimestampAndExpiresOnTime() {
        store.set("k", bytes("v"));

        Command.Outcome outcome = pexpireAt.executeWithOutcome(
                List.of(BulkString.of("k"), BulkString.of("1005000")));

        assertThat(outcome.response()).isEqualTo(new RespInteger(1));
        assertThat(outcome.aofCommand()).containsExactly(
                BulkString.of("PEXPIREAT"), BulkString.of("k"), BulkString.of("1005000"));
        assertThat(store.getTtlSeconds("k")).isEqualTo(5);

        clock.advanceSeconds(5);
        assertThat(store.get("k")).isNull();
    }

    @Test
    void pastTimestampDeletesExistingKeyAndLogsDelToAof() {
        store.set("k", bytes("v"));

        Command.Outcome outcome = pexpireAt.executeWithOutcome(
                List.of(BulkString.of("k"), BulkString.of("999000")));

        assertThat(outcome.response()).isEqualTo(new RespInteger(1));
        assertThat(outcome.aofCommand()).containsExactly(BulkString.of("DEL"), BulkString.of("k"));
        assertThat(store.exists("k")).isFalse();
    }

    @Test
    void missingKeyReturnsZeroAndWritesNothingToAof() {
        Command.Outcome outcome = pexpireAt.executeWithOutcome(
                List.of(BulkString.of("missing"), BulkString.of("1005000")));

        assertThat(outcome.response()).isEqualTo(new RespInteger(0));
        assertThat(outcome.aofCommand()).isNull();
    }

    @Test
    void validatesArguments() {
        assertThat(pexpireAt.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'pexpireat' command"));
        assertThat(pexpireAt.execute(List.of(BulkString.of("k"), BulkString.of("abc"))))
                .isEqualTo(new SimpleError("ERR value is not an integer or out of range"));
    }
}

