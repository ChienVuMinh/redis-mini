package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncrCommandTest {

    private static final SimpleError NOT_AN_INTEGER = new SimpleError("ERR value is not an integer or out of range");

    private final DataStore store = new DataStore();
    private final IncrCommand incr = new IncrCommand(store);

    private RespValue incr(String key) {
        return incr.execute(List.of(BulkString.of(key)));
    }

    private void set(String key, String value) {
        store.set(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private String valueOf(String key) {
        return new String(store.get(key), StandardCharsets.UTF_8);
    }

    @Test
    void missingKeyStartsFromZero() {
        assertThat(incr("counter")).isEqualTo(new RespInteger(1));
        assertThat(incr("counter")).isEqualTo(new RespInteger(2));
    }

    @Test
    void storesResultAsDecimalText() {
        // Redis không có kiểu số: GET sau INCR vẫn ra chuỗi "11"
        set("k", "10");

        assertThat(incr("k")).isEqualTo(new RespInteger(11));
        assertThat(valueOf("k")).isEqualTo("11");
    }

    @Test
    void worksWithNegativeNumbers() {
        set("k", "-1");

        assertThat(incr("k")).isEqualTo(new RespInteger(0));
        assertThat(valueOf("k")).isEqualTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "1.5", "+5", "007", "-0", " 5", "5 ", "9223372036854775808"})
    void rejectsValuesThatAreNotStrictIntegers(String value) {
        set("k", value);

        assertThat(incr("k")).isEqualTo(NOT_AN_INTEGER);
        // Lỗi thì value phải giữ nguyên
        assertThat(valueOf("k")).isEqualTo(value);
    }

    @Test
    void refusesToOverflow() {
        set("k", Long.toString(Long.MAX_VALUE));

        assertThat(incr("k")).isEqualTo(new SimpleError("ERR increment or decrement would overflow"));
        assertThat(valueOf("k")).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void requiresExactlyOneArgument() {
        SimpleError error = new SimpleError("ERR wrong number of arguments for 'incr' command");

        assertThat(incr.execute(List.of())).isEqualTo(error);
        assertThat(incr.execute(List.of(BulkString.of("a"), BulkString.of("b")))).isEqualTo(error);
    }
}
