package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Test lệnh trực tiếp, không cần socket hay server: chỉ là gọi một hàm. */
class PingCommandTest {

    private final PingCommand ping = new PingCommand();

    @Test
    void withoutArgumentsRepliesPong() {
        assertThat(ping.execute(List.of())).isEqualTo(new SimpleString("PONG"));
    }

    @Test
    void withMessageEchoesItBack() {
        assertThat(ping.execute(List.of(BulkString.of("xin chào")))).isEqualTo(BulkString.of("xin chào"));
    }

    @Test
    void withTooManyArgumentsIsAnError() {
        assertThat(ping.execute(List.of(BulkString.of("a"), BulkString.of("b"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'ping' command"));
    }
}
