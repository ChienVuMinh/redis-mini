package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespWriterTest {

    /** Ghi rồi đổi sang String để so sánh cho dễ đọc. */
    private static String encode(RespValue value) {
        return new String(RespWriter.encode(value), StandardCharsets.UTF_8);
    }

    @Test
    void writesSimpleString() {
        assertThat(encode(new SimpleString("OK"))).isEqualTo("+OK\r\n");
    }

    @Test
    void writesSimpleError() {
        assertThat(encode(new SimpleError("ERR unknown command"))).isEqualTo("-ERR unknown command\r\n");
    }

    @Test
    void writesIntegers() {
        assertThat(encode(new RespInteger(42))).isEqualTo(":42\r\n");
        assertThat(encode(new RespInteger(-7))).isEqualTo(":-7\r\n");
    }

    @Test
    void writesBulkString() {
        assertThat(encode(BulkString.of("hello"))).isEqualTo("$5\r\nhello\r\n");
    }

    @Test
    void bulkStringLengthIsInBytes() {
        // "chào" = 4 ký tự nhưng 5 byte, nên phải là $5
        assertThat(encode(BulkString.of("chào"))).isEqualTo("$5\r\nchào\r\n");
    }

    @Test
    void bulkStringMayContainNewlines() {
        // Bulk String báo độ dài trước nên chứa \r\n bên trong vẫn an toàn
        assertThat(encode(BulkString.of("a\r\nb"))).isEqualTo("$4\r\na\r\nb\r\n");
    }

    @Test
    void writesEmptyBulkString() {
        assertThat(encode(BulkString.of(""))).isEqualTo("$0\r\n\r\n");
    }

    @Test
    void writesNullBulkString() {
        assertThat(encode(BulkString.NULL)).isEqualTo("$-1\r\n");
    }

    @Test
    void writesCommandAsArrayOfBulkStrings() {
        RespArray command = RespArray.of(BulkString.of("SET"), BulkString.of("name"), BulkString.of("Chien"));

        assertThat(encode(command)).isEqualTo("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nChien\r\n");
    }

    @Test
    void writesEmptyArray() {
        assertThat(encode(new RespArray(List.of()))).isEqualTo("*0\r\n");
    }

    @Test
    void writesNestedArrayWithMixedTypes() {
        RespArray nested = RespArray.of(
                new RespInteger(1),
                RespArray.of(new SimpleString("OK"), BulkString.NULL));

        assertThat(encode(nested)).isEqualTo("*2\r\n:1\r\n*2\r\n+OK\r\n$-1\r\n");
    }

    @Test
    void rejectsSimpleStringContainingNewline() {
        assertThatThrownBy(() -> encode(new SimpleString("OK\r\nhacked")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encode(new SimpleError("bad\nerror")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
