package ca.int13.librenmsmcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.config.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        long millis = 1_000_000;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void windowSlidesRatherThanResetting() {
        var clock = new ManualClock();
        var limiter = new RateLimiter(new Config.RateLimit(2, Duration.ofSeconds(60)), clock);
        assertThat(limiter.acquire("a")).isEmpty();
        clock.millis += 30_000;
        assertThat(limiter.acquire("a")).isEmpty();
        assertThat(limiter.acquire("a")).hasValue(31); // first hit leaves the window in 30 s
        clock.millis += 30_001;
        assertThat(limiter.acquire("a")).as("first hit expired").isEmpty();
        assertThat(limiter.acquire("a")).isPresent();
    }

    @Test
    void keysAreIndependent() {
        var limiter = new RateLimiter(new Config.RateLimit(1, Duration.ofSeconds(60)), new ManualClock());
        assertThat(limiter.acquire("a")).isEmpty();
        assertThat(limiter.acquire("a")).isPresent();
        assertThat(limiter.acquire("b")).isEmpty();
    }

    @Test
    void idleKeysAreEvicted() {
        var clock = new ManualClock();
        var limiter = new RateLimiter(new Config.RateLimit(5, Duration.ofSeconds(60)), clock);
        for (int i = 0; i < 100; i++) {
            limiter.acquire("caller-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100);
        clock.millis += 120_000;
        limiter.acquire("new");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
