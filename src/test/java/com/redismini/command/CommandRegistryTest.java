package com.redismini.command;

import com.redismini.persistence.AofWriter;
import com.redismini.persistence.FsyncPolicy;
import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRegistryTest {

    /** Lệnh giả để test registry mà không phụ thuộc lệnh thật: trả về số tham số nhận được. */
    private record CountArgsCommand(String name) implements Command {
        @Override
        public RespValue execute(List<BulkString> args) {
            return new RespInteger(args.size());
        }
    }

    private static List<BulkString> command(String... parts) {
        return Arrays.stream(parts).map(BulkString::of).toList();
    }

    @Test
    void dispatchesToCommandByNameAndPassesOnlyArguments() {
        CommandRegistry registry = new CommandRegistry(List.of(new CountArgsCommand("COUNT")));

        // Tên lệnh bị bỏ đi, lệnh chỉ nhận 3 tham số sau nó
        assertThat(registry.execute(command("COUNT", "a", "b", "c"))).isEqualTo(new RespInteger(3));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        CommandRegistry registry = new CommandRegistry(List.of(new CountArgsCommand("COUNT")));

        assertThat(registry.execute(command("count"))).isEqualTo(new RespInteger(0));
        assertThat(registry.execute(command("CoUnT"))).isEqualTo(new RespInteger(0));
    }

    @Test
    void registeringLowercaseNameStillWorks() {
        CommandRegistry registry = new CommandRegistry(List.of(new CountArgsCommand("count")));

        assertThat(registry.execute(command("COUNT"))).isEqualTo(new RespInteger(0));
    }

    @Test
    void unknownCommandUsesRedisErrorFormat() {
        CommandRegistry registry = new CommandRegistry(List.of());

        assertThat(registry.execute(command("FOO", "a", "b")))
                .isEqualTo(new SimpleError("ERR unknown command 'FOO', with args beginning with: 'a' 'b' "));
        assertThat(registry.execute(command("FOO")))
                .isEqualTo(new SimpleError("ERR unknown command 'FOO', with args beginning with: "));
    }

    @Test
    void unknownCommandErrorNeverContainsNewlines() {
        CommandRegistry registry = new CommandRegistry(List.of());

        RespValue reply = registry.execute(command("A\r\nB", "x\ny"));

        assertThat(reply).isInstanceOfSatisfying(SimpleError.class,
                error -> assertThat(error.message()).doesNotContain("\r", "\n"));
    }

    @Test
    void rejectsDuplicateCommandNames() {
        assertThatThrownBy(() -> new CommandRegistry(List.of(
                new CountArgsCommand("COUNT"), new CountArgsCommand("count"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COUNT");
    }

    @Test
    void defaultRegistryHasPing() {
        assertThat(CommandRegistry.createDefault(new DataStore()).execute(command("PING")))
                .isEqualTo(new SimpleString("PONG"));
    }

    @Test
    void defaultRegistryHasTtlCommands() {
        CommandRegistry registry = CommandRegistry.createDefault(new DataStore());
        // Kiểm tra các lệnh TTL mới đã được đăng ký trong registry mặc định
        assertThat(registry.execute(command("TTL", "k"))).isEqualTo(new RespInteger(-2));
        assertThat(registry.execute(command("EXPIRE", "k", "10"))).isEqualTo(new RespInteger(0));
        assertThat(registry.execute(command("PERSIST", "k"))).isEqualTo(new RespInteger(0));
        assertThat(registry.execute(command("PEXPIREAT", "k", "10000"))).isEqualTo(new RespInteger(0));
        assertThat(registry.execute(command("BGREWRITEAOF")))
                .isEqualTo(new SimpleError("ERR AOF is not enabled"));
    }

    @Test
    void logsOnlySuccessfulMutatingCommandsToAof(@TempDir Path tempDir) throws IOException {
        Path aofFile = tempDir.resolve("registry.aof");
        DataStore store = new DataStore();
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(store, writer);

            // Lệnh đọc & lỗi & no-op không được ghi vào AOF
            registry.execute(command("PING"));
            registry.execute(command("GET", "missing"));
            registry.execute(command("DEL", "missing"));
            registry.execute(command("EXPIRE", "missing", "10"));
            registry.execute(command("PERSIST", "missing"));
            registry.execute(command("SET", "only_key"));
            registry.execute(command("UNKNOWN"));

            // Lệnh ghi thành công mới được ghi
            registry.execute(command("SET", "k", "v"));
            registry.execute(command("INCR", "num"));
            registry.execute(command("DEL", "k", "missing"));

            // replay() không ghi ngược vào AOF
            registry.replay(command("SET", "replayed", "1"));
        }

        List<List<BulkString>> logged = new ArrayList<>();
        try (var in = new BufferedInputStream(Files.newInputStream(aofFile))) {
            RespParser parser = new RespParser(in);
            List<BulkString> cmd;
            while ((cmd = parser.readCommand()) != null) {
                logged.add(cmd);
            }
        }

        assertThat(logged).containsExactly(
                command("SET", "k", "v"),
                command("INCR", "num"),
                command("DEL", "k")
        );
    }
}
