package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One rolling window per (key, rule), plus per-key Retry-After pauses. Rolling rather than a refilling
 * bucket: the count in any trailing window never exceeds permits, which satisfies a server counting rolling
 * or fixed windows; a bucket dripping permits back earns 429s. In memory, so windows restart empty:
 * {@code app.ratelimit.store=memory}, for tests or a setup without Redis.
 */
@ApplicationScoped
@ProviderImpl
public class SlidingWindowRateLimiter extends AbstractRateLimiter {

    private static final int PURGE_THRESHOLD = 256;
    private static final Duration PURGE_IDLE_AFTER = Duration.ofHours(1);

    /** {@code "<key>#<windowSeconds>"} → window; one per rule. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** {@code "<key>"} → when a Retry-After said to resume. */
    private final Map<String, Instant> penalties = new ConcurrentHashMap<>();

    private final Object lock = new Object();

    @Override
    Duration reserve(RateLimit limit, Instant now) {
        synchronized (lock) {
            Duration wait = penaltyRemaining(limit.key(), now);
            if (limit.policy().isUnlimited()) {
                return wait;
            }
            for (RateLimitRule rule : limit.policy().rules()) {
                Window window = windowFor(limit.key(), rule, now);
                window.evictExpired(rule, now);
                if (window.isFull(rule)) {
                    Duration need = window.timeToFreeSlot(rule, now);
                    if (need.compareTo(wait) > 0) {
                        wait = need;
                    }
                }
            }
            if (!wait.isZero()) {
                return wait;
            }
            for (RateLimitRule rule : limit.policy().rules()) {
                windowFor(limit.key(), rule, now).record(now, rule);
            }
            return Duration.ZERO;
        }
    }

    @Override
    void storePenalty(RateLimitKey key, Instant until) {
        penalties.merge(key.value(), until, (a, b) -> a.isAfter(b) ? a : b);
    }

    private Duration penaltyRemaining(RateLimitKey key, Instant now) {
        Instant until = penalties.get(key.value());
        if (until == null) {
            return Duration.ZERO;
        }
        if (until.isAfter(now)) {
            return Duration.between(now, until);
        }
        penalties.remove(key.value());
        return Duration.ZERO;
    }

    private Window windowFor(RateLimitKey key, RateLimitRule rule, Instant now) {
        if (windows.size() >= PURGE_THRESHOLD) {
            purgeIdle(now);
        }
        return windows.computeIfAbsent(key.value() + "#" + rule.window().toSeconds(),
                k -> new Window(rule.permits(), now));
    }

    /** Safe: a recreated window starts empty, which is the state being discarded. */
    private void purgeIdle(Instant now) {
        Instant cutoff = now.minus(PURGE_IDLE_AFTER);
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Window window = it.next().getValue();
            if (window.lastTouched.isBefore(cutoff) && window.size == 0) {
                it.remove();
            }
        }
        penalties.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }

    public int availableNow(RateLimitKey key, RateLimitRule rule) {
        synchronized (lock) {
            Instant now = clock.instant();
            Window window = windowFor(key, rule, now);
            window.evictExpired(rule, now);
            return Math.max(0, rule.permits() - window.size);
        }
    }

    /**
     * Admission instants in a ring buffer sized to the highest ceiling that charged it. Fullness is judged
     * against the caller's rule, not the buffer: two rules can share this counter at different ceilings.
     */
    private static final class Window {

        private int capacity;
        private Instant[] admitted;
        private int head;
        private int size;
        private Instant lastTouched;

        private Window(int capacity, Instant now) {
            this.capacity = capacity;
            this.admitted = new Instant[capacity];
            this.lastTouched = now;
        }

        private void evictExpired(RateLimitRule rule, Instant now) {
            Instant cutoff = now.minus(rule.window());
            while (size > 0 && !admitted[head].isAfter(cutoff)) {
                admitted[head] = null;
                head = (head + 1) % capacity;
                size--;
            }
            lastTouched = now;
        }

        private boolean isFull(RateLimitRule rule) {
            return size >= rule.permits();
        }

        /**
         * The oldest admission only when the count is exactly at the ceiling; above it, the one {@code size -
         * permits} further in.
         */
        private Duration timeToFreeSlot(RateLimitRule rule, Instant now) {
            Instant freeing = admitted[(head + size - rule.permits()) % capacity];
            return Duration.between(now, freeing.plus(rule.window()));
        }

        private void record(Instant now, RateLimitRule rule) {
            if (size == capacity) {
                grow(Math.max(capacity + 1, rule.permits()));
            }
            admitted[(head + size) % capacity] = now;
            size++;
            lastTouched = now;
        }

        private void grow(int newCapacity) {
            Instant[] larger = new Instant[newCapacity];
            for (int i = 0; i < size; i++) {
                larger[i] = admitted[(head + i) % capacity];
            }
            admitted = larger;
            capacity = newCapacity;
            head = 0;
        }
    }
}
