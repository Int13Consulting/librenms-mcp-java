package ca.int13.librenmsmcp.http;

import ca.int13.librenmsmcp.config.Config;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sliding-window limit per key (one key per authenticated principal): at most
 * {@code maxRequests} in any {@code window}. Keys idle for a whole window are dropped, so memory is
 * bounded by the number of recently active callers.
 */
final class RateLimiter {
    private final int max;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    RateLimiter(Config.RateLimit limit, Clock clock) {
        this.max = limit.maxRequests();
        this.windowMillis = limit.window().toMillis();
        this.clock = clock;
    }

    /** Records a request; returns empty if allowed, or the seconds until one is allowed again. */
    OptionalLong acquire(String key) {
        long now = clock.millis();
        sweep(now);
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                long retryMillis = times.peekFirst() + windowMillis - now;
                return OptionalLong.of(
                        Math.max(1, Duration.ofMillis(retryMillis).toSeconds() + 1));
            }
            times.addLast(now);
            return OptionalLong.empty();
        }
    }

    private void sweep(long now) {
        if (now - lastSweep < windowMillis) {
            return;
        }
        lastSweep = now;
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || last <= now - windowMillis;
            }
        });
    }

    int trackedKeys() {
        return hits.size();
    }
}
