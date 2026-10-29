package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SetCommandTest {

    private final DataStore store = new DataStore();
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
        // Redis thật cũng trả "syntax error" cho tuỳ chọn không hiểu. EX/PX sẽ có ở Giai đoạn 4
        assertThat(set.execute(List.of(BulkString.of("k"), BulkString.of("v"), BulkString.of("FOO"))))
                .isEqualTo(new SimpleError("ERR syntax error"));
        assertThat(store.exists("k")).isFalse();
    }
}
