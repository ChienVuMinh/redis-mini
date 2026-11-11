package com.redismini.command;

import com.redismini.TestClock;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SetCommandTest {

    private final TestClock clock = TestClock.ofEpochMilli(1_000_000L);
    private final DataStore store = new DataStore(clock);
    private final SetCommand set = new SetCommand(store);

    @Test
    void storesValueAndRepliesOk() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v")))).isEqualTo(new SimpleString("OK"));

        assertThat(store.get("k")).isEqualTo("v".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void overwritesExistingValue() {
        set.execute(List.of(BulkString.of("k"), BulkString.of("1")));
        set.execute(List.of(BulkString.of("k"), BulkString.of("2")));

        assertThat(store.get("k")).isEqualTo("2".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storesBinaryValueUnchanged() {
        byte[] binary = {0, (byte) 0xFF, '\r', '\n'};
        set.execute(List.of(BulkString.of("bin"), new BulkString(binary)));

        assertThat(store.get("bin")).isEqualTo(binary);
    }

    @Test
    void tooFewArgumentsIsAnError() {
        assertThat(set.execute(List.of(BulkString.of("k"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'set' command"));
    }

    @Test
    void extraArgumentsAreASyntaxError() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("FOO"))))
                .isEqualTo(new SimpleError("ERR syntax error"));
        assertThat(store.exists("k")).isFalse();

        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("10"), BulkString.of("EXTRA"))))
                .isEqualTo(new SimpleError("ERR syntax error"));
    }

    @Test
    void unknownOptionIsSyntaxError() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("PX"), BulkString.of("1000"))))
                .isEqualTo(new SimpleError("ERR syntax error"));
    }

    @Test
    void setWithExSetsTtlAndExpires() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("10"))))
                .isEqualTo(new SimpleString("OK"));

        assertThat(store.get("k")).isEqualTo("v".getBytes(StandardCharsets.UTF_8));
        assertThat(store.getTtlSeconds("k")).isEqualTo(10);

        clock.advanceSeconds(10);
        assertThat(store.get("k")).isNull();
    }

    @Test
    void setWithExIsCaseInsensitive() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("ex"), BulkString.of("5"))))
                .isEqualTo(new SimpleString("OK"));
        assertThat(store.getTtlSeconds("k")).isEqualTo(5);
    }

    @Test
    void setWithoutExClearsExistingTtl() {
        set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("10")));
        assertThat(store.getTtlSeconds("k")).isGreaterThan(0);

        // SET đè không có EX -> xoá TTL
        set.execute(List.of(BulkString.of("k"), BulkString.of("v2")));
        assertThat(store.getTtlSeconds("k")).isEqualTo(-1);
    }

    @Test
    void setWithExRejectsNonIntegerSeconds() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("abc"))))
                .isEqualTo(new SimpleError("ERR value is not an integer or out of range"));
        assertThat(store.exists("k")).isFalse();
    }

    @Test
    void setWithExRejectsZeroOrNegativeSeconds() {
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("0"))))
                .isEqualTo(new SimpleError("ERR invalid expire time in 'set' command"));

        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("-5"))))
                .isEqualTo(new SimpleError("ERR invalid expire time in 'set' command"));

        assertThat(store.exists("k")).isFalse();
    }

    @Test
    void setWithExProducesPxatAofCommand() {
        Command.Outcome outcome = set.executeWithOutcome(
                List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("EX"), BulkString.of("10")));

        assertThat(outcome.response()).isEqualTo(new SimpleString("OK"));
        assertThat(outcome.aofCommand()).containsExactly(
                BulkString.of("SET"),
                BulkString.of("k"),
                BulkString.of("v"),
                BulkString.of("PXAT"),
                BulkString.of("1010000")
        );
    }

    @Test
    void setWithPxatSetsAbsoluteExpiration() {
        assertThat(set.execute(List.of(
                BulkString.of("k"), BulkString.of("v"), BulkString.of("PXAT"), BulkString.of("1005000"))))
                .isEqualTo(new SimpleString("OK"));

        assertThat(store.getTtlSeconds("k")).isEqualTo(5);
        clock.advanceSeconds(5);
        assertThat(store.get("k")).isNull();
    }
}
