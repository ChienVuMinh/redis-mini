package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import org.junit.jupiter.api.Test;

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
        assertThat(CommandRegistry.createDefault().execute(command("PING")))
                .isEqualTo(new SimpleString("PONG"));
    }
}
