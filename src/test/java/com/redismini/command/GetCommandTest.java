package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetCommandTest {

    private final DataStore store = new DataStore();
    private final GetCommand get = new GetCommand(store);

    @Test
    void returnsStoredValue() {
        store.set("k", "chào".getBytes(StandardCharsets.UTF_8));

        assertThat(get.execute(List.of(BulkString.of("k")))).isEqualTo(BulkString.of("chào"));
    }

    @Test
    void missingKeyReturnsNil() {
        assertThat(get.execute(List.of(BulkString.of("nope")))).isEqualTo(BulkString.NULL);
    }

    @Test
    void emptyValueIsNotNil() {
        store.set("k", new byte[0]);

        assertThat(get.execute(List.of(BulkString.of("k")))).isEqualTo(BulkString.of(""));
    }

    @Test
    void requiresExactlyOneArgument() {
        SimpleError error = new SimpleError("ERR wrong number of arguments for 'get' command");

        assertThat(get.execute(List.of())).isEqualTo(error);
        assertThat(get.execute(List.of(BulkString.of("a"), BulkString.of("b")))).isEqualTo(error);
    }
}
