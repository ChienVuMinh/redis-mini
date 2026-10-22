package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RespValueTest {

    @Test
    void bulkStringsWithSameContentAreEqual() {
        assertThat(BulkString.of("PING")).isEqualTo(BulkString.of("PING"));
    }

    @Test
    void bulkStringLengthIsCountedInBytesNotCharacters() {
        BulkString value = BulkString.of("chào");

        // "chào" có 4 ký tự nhưng "à" chiếm 2 byte trong UTF-8
        assertThat(value.asString()).hasSize(4);
        assertThat(value.data()).hasSize(5);
    }

    @Test
    void nullBulkStringHasNoData() {
        assertThat(BulkString.NULL.isNull()).isTrue();
        assertThat(BulkString.NULL.asString()).isNull();
    }

    @Test
    void commandIsArrayOfBulkStrings() {
        RespArray command = RespArray.of(BulkString.of("SET"), BulkString.of("name"), BulkString.of("Chien"));

        assertThat(command.elements()).hasSize(3);
        assertThat(command).isEqualTo(RespArray.of(BulkString.of("SET"), BulkString.of("name"), BulkString.of("Chien")));
    }
}
