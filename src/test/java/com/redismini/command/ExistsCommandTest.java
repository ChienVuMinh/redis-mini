package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExistsCommandTest {

    private final DataStore store = new DataStore();
    private final ExistsCommand exists = new ExistsCommand(store);

    @Test
    void countsExistingKeys() {
        store.set("a", new byte[0]);

        assertThat(exists.execute(List.of(BulkString.of("a"), BulkString.of("b")))).isEqualTo(new RespInteger(1));
    }

    @Test
    void repeatedKeyIsCountedEachTime() {
        // Khác DEL: EXISTS không đổi dữ liệu, nên lần hỏi thứ hai vẫn thấy key
        store.set("a", new byte[0]);

        assertThat(exists.execute(List.of(BulkString.of("a"), BulkString.of("a")))).isEqualTo(new RespInteger(2));
    }

    @Test
    void requiresAtLeastOneKey() {
        assertThat(exists.execute(List.of()))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'exists' command"));
    }
}
