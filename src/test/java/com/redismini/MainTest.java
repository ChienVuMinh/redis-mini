package com.redismini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void usesDefaultPortWhenNoArgs() {
        assertThat(Main.parsePort(new String[]{})).isEqualTo(6380);
    }

    @Test
    void readsPortFromArgs() {
        assertThat(Main.parsePort(new String[]{"--port", "7000"})).isEqualTo(7000);
    }
}
