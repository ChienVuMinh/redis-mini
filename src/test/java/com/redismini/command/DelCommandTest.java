package com.redismini.command;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DelCommandTest {

    private final DataStore store = new DataStore();
    private final DelCommand del = new DelCommand(store);

    @Test
    void deletesKeysAndCountsOnlyThoseThatExisted() {
        store.set("a", new byte[0]);
        store.set("b", new byte[0]);

        assertThat(del.execute(List.of(BulkString.of("a"), BulkString.of("b"), BulkString.of("c"))))
                .isEqualTo(new RespInteger(2));
        assertThat(store.size()).isZero();
    }

    @Test
    void sameKeyTwiceIsCountedOnce() {
        // Lần xoá thứ hai không còn gì để xoá
        store.set("a", new byte[0]);

        assertThat(del.execute(List.of(BulkString.of("a"), BulkString.of("a")))).isEqualTo(new RespInteger(1));
    }

    @Test
    void requiresAtLeastOneKey() {
        assertThat(del.execute(List.of()))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'del' command"));
    }
}
