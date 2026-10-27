package com.redismini.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DataStoreTest {

    private final DataStore store = new DataStore();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertThat(store.get("nope")).isNull();
    }

    @Test
    void setThenGet() {
        store.set("name", bytes("chiến"));

        assertThat(store.get("name")).isEqualTo(bytes("chiến"));
    }

    @Test
    void setOverwritesExistingValue() {
        store.set("k", bytes("1"));
        store.set("k", bytes("2"));

        assertThat(store.get("k")).isEqualTo(bytes("2"));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void storesBinaryValuesAsIs() {
        // Value là byte bất kỳ, kể cả byte 0 và \r\n
        byte[] binary = {0, (byte) 0xFF, '\r', '\n', 42};
        store.set("bin", binary);

        assertThat(store.get("bin")).isEqualTo(binary);
    }

    @Test
    void deleteRemovesKeyAndReportsWhetherItExisted() {
        store.set("k", bytes("v"));

        assertThat(store.delete("k")).isTrue();
        assertThat(store.delete("k")).isFalse();
        assertThat(store.get("k")).isNull();
    }

    @Test
    void existsAndSize() {
        assertThat(store.exists("a")).isFalse();
        assertThat(store.size()).isZero();

        store.set("a", bytes("1"));
        store.set("b", bytes("2"));

        assertThat(store.exists("a")).isTrue();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void emptyValueIsDifferentFromMissingKey() {
        // SET k "" là hợp lệ: key tồn tại, value rỗng. Khác với key không tồn tại (nil)
        store.set("k", bytes(""));

        assertThat(store.exists("k")).isTrue();
        assertThat(store.get("k")).isEmpty();
    }
}
