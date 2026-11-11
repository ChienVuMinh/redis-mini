package com.redismini.persistence;

import com.redismini.TestClock;
import com.redismini.command.CommandRegistry;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AofLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsZeroWhenAofFileDoesNotExist() throws IOException {
        Path missing = tempDir.resolve("missing.aof");
        DataStore store = new DataStore();

        int count = AofLoader.load(missing, CommandRegistry.createDefault(store));

        assertThat(count).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void replaysCommandsAndPreservesExactTtlAcrossRestart() throws IOException {
        Path aofFile = tempDir.resolve("appendonly.aof");
        TestClock clock = TestClock.ofEpochMilli(1_000_000L);

        // Phase 1: chạy server đầu tiên ghi dữ liệu
        DataStore firstStore = new DataStore(clock);
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(firstStore, writer);
            registry.execute(cmd("SET", "name", "Chien"));
            registry.execute(cmd("INCR", "visits"));
            registry.execute(cmd("INCR", "visits"));
            registry.execute(cmd("SET", "temp_short", "1", "EX", "5"));   // hết hạn ở 1_005_000
            registry.execute(cmd("SET", "temp_long", "2", "EX", "20"));   // hết hạn ở 1_020_000
            registry.execute(cmd("SET", "persisted", "p", "EX", "10"));
            registry.execute(cmd("PERSIST", "persisted"));
            registry.execute(cmd("SET", "removed", "x"));
            registry.execute(cmd("DEL", "removed"));
        }

        // Giả sử server tắt 8 giây rồi mới bật lại -> temp_short đã hết hạn, temp_long còn 12s
        clock.advanceSeconds(8);

        // Phase 2: server mới load lại từ file AOF
        DataStore restoredStore = new DataStore(clock);
        int loaded = AofLoader.load(aofFile, CommandRegistry.createDefault(restoredStore));

        assertThat(loaded).isEqualTo(9);
        assertThat(restoredStore.get("name")).isEqualTo("Chien".getBytes(StandardCharsets.UTF_8));
        assertThat(restoredStore.get("visits")).isEqualTo("2".getBytes(StandardCharsets.UTF_8));
        assertThat(restoredStore.get("temp_short")).isNull();
        assertThat(restoredStore.get("temp_long")).isEqualTo("2".getBytes(StandardCharsets.UTF_8));
        assertThat(restoredStore.getTtlSeconds("temp_long")).isEqualTo(12);
        assertThat(restoredStore.get("persisted")).isEqualTo("p".getBytes(StandardCharsets.UTF_8));
        assertThat(restoredStore.getTtlSeconds("persisted")).isEqualTo(-1);
        assertThat(restoredStore.exists("removed")).isFalse();
    }

    @Test
    void ignoresAndTruncatesIncompleteTailCommandAfterCrash() throws IOException {
        Path aofFile = tempDir.resolve("crashed.aof");

        DataStore store = new DataStore();
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(store, writer);
            registry.execute(cmd("SET", "k1", "v1"));
            registry.execute(cmd("SET", "k2", "v2"));
        }
        long validSize = Files.size(aofFile);

        // Mô phỏng server bị kill -9 đúng lúc đang ghi dở lệnh thứ 3
        byte[] incompleteTail = "*3\r\n$3\r\nSET\r\n$2\r\nk3\r\n$10\r\nhel".getBytes(StandardCharsets.UTF_8);
        Files.write(aofFile, incompleteTail, StandardOpenOption.APPEND);
        assertThat(Files.size(aofFile)).isGreaterThan(validSize);

        // Khởi động lại: đọc được 2 lệnh hợp lệ và tự cắt bỏ phần đuôi bị hỏng
        DataStore restored = new DataStore();
        int loaded = AofLoader.load(aofFile, CommandRegistry.createDefault(restored));

        assertThat(loaded).isEqualTo(2);
        assertThat(restored.get("k1")).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("k2")).isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
        assertThat(restored.exists("k3")).isFalse();
        assertThat(Files.size(aofFile)).isEqualTo(validSize);

        // File sau khi truncate vẫn tiếp tục append và load lại bình thường
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(restored, writer);
            registry.execute(cmd("SET", "k3", "v3"));
        }
        DataStore finalStore = new DataStore();
        assertThat(AofLoader.load(aofFile, CommandRegistry.createDefault(finalStore))).isEqualTo(3);
        assertThat(finalStore.get("k3")).isEqualTo("v3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadDoesNotWriteBackToAof() throws IOException {
        Path aofFile = tempDir.resolve("norewrite.aof");
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(new DataStore(), writer);
            registry.execute(cmd("SET", "k", "v"));
        }
        long sizeBeforeLoad = Files.size(aofFile);

        DataStore restored = new DataStore();
        try (AofWriter writer = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandRegistry registry = CommandRegistry.createDefault(restored, writer);
            AofLoader.load(aofFile, registry);
        }

        assertThat(Files.size(aofFile)).isEqualTo(sizeBeforeLoad);
    }

    private static List<BulkString> cmd(String... parts) {
        return java.util.Arrays.stream(parts).map(BulkString::of).toList();
    }
}

