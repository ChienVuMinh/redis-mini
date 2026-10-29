package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EchoCommandTest {

    private final EchoCommand echo = new EchoCommand();

    @Test
    void repliesWithTheMessage() {
        assertThat(echo.execute(List.of(BulkString.of("xin chào")))).isEqualTo(BulkString.of("xin chào"));
    }

    @Test
    void requiresExactlyOneArgument() {
        SimpleError error = new SimpleError("ERR wrong number of arguments for 'echo' command");

        assertThat(echo.execute(List.of())).isEqualTo(error);
        assertThat(echo.execute(List.of(BulkString.of("a"), BulkString.of("b")))).isEqualTo(error);
    }
}
