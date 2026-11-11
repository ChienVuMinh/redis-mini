package com.redismini.command;

import com.redismini.persistence.AofWriter;
import com.redismini.persistence.FsyncPolicy;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BgrewriteaofCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsErrorWhenAofIsDisabled() {
        BgrewriteaofCommand command = new BgrewriteaofCommand(new DataStore(), null);

        assertThat(command.execute(List.of()))
                .isEqualTo(new SimpleError("ERR AOF is not enabled"));
    }

    @Test
    void returnsErrorWhenGivenArguments() {
        BgrewriteaofCommand command = new BgrewriteaofCommand(new DataStore(), null);

        assertThat(command.execute(List.of(BulkString.of("extra"))))
                .isEqualTo(new SimpleError("ERR wrong number of arguments for 'bgrewriteaof' command"));
    }

    @Test
    void startsBackgroundRewriteAndReturnsStatusString() throws Exception {
        Path aofFile = tempDir.resolve("appendonly.aof");
        DataStore store = new DataStore();
        store.set("k", "v".getBytes());

        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            BgrewriteaofCommand command = new BgrewriteaofCommand(store, writer);

            assertThat(command.execute(List.of()))
                    .isEqualTo(new SimpleString("Background append only file rewriting started"));

            writer.getRewriteFuture().get(5, TimeUnit.SECONDS);
            assertThat(writer.isRewriting()).isFalse();
        }
    }
}

