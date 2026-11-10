package com.redismini;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Đồng hồ giả lập dùng trong test, có thể chủ động tua thời gian mà không cần Thread.sleep.
 */
public class TestClock extends Clock {
    private Instant now;
    private final ZoneId zone = ZoneId.of("UTC");

    public TestClock(Instant initial) {
        this.now = initial;
    }

    public static TestClock ofEpochMilli(long millis) {
        return new TestClock(Instant.ofEpochMilli(millis));
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

