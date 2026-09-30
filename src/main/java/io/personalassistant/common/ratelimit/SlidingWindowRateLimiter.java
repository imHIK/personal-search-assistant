package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RateLimiter}: one rolling window per (key, rule), plus a per-key pause honouring a
 * server's {@code Retry-After}. Structured after {@code InMemoryPermitService} — a
 * {@link ConcurrentHashMap} of counters guarded by one lock, with expiry doing the reclaiming so a
 * crashed or slow caller leaves nothing to clean up.
 *
 * <p><strong>The window is rolling, not a refilling bucket.</strong> A window remembers when each of its
 * {@code permits} admissions happened and admits again only once the oldest of them has aged out, so the
 * count in <em>any</em> trailing {@code window} is never above {@code permits}. That is the tighter of
 * the two ways a service can count, which is the whole reason it is enforced this way: a server counting
 * a rolling window is satisfied exactly, and one counting fixed windows — which tolerates
 * 2&times;{@code permits} across a boundary — is satisfied with room to spare. One implementation is
 * therefore correct against both, with no per-provider knob to get wrong.
 *
 * <p>A bucket that drips its permits back at the sustained rate looks gentler and is not: at
 * {@code 60/1d} it hands back a permit every 24 minutes, so the 61st call of the day lands while a
 * server counting the trailing day still sees 60, and earns a 429 that the local limiter believed it had
 * avoided. The rolling window instead returns all 60 at once, a day after they were spent. Work arrives
 * in usable batches rather than in single permits too small to finish one entity, at the cost of a
 * lumpier {@code retryAt} — which is exactly what the callers persist and resume from.
 *
 * <p><strong>Not the shipped store.</strong> The windows die with the JVM, so every restart — and every
 * {@code quarkusDev} live reload — starts a long window empty while the provider's own count carries on.
 * Selected by {@code app.ratelimit.store=memory}, for tests and a setup without Redis;
 * {@link RedisRateLimiter} is the same semantics with counters that outlive the process.
 */
@ApplicationScoped
@ProviderImpl
public class SlidingWindowRateLimiter extends AbstractRateLimiter {

    /** Window count past which idle entries are swept, so deleted accounts don't accumulate. */
    private static final int PURGE_THRESHOLD = 256;
    private static final Duration PURGE_IDLE_AFTER = Duration.ofHours(1);

    /** {@code "<key>#<windowSeconds>"} → window. One per rule, which is how multi-window composes. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** {@code "<key>"} → the instant a {@code Retry-After} said to resume. */
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

    /**
     * Drop windows that hold nothing and have been untouched for a while. Safe by construction: a
     * recreated window starts empty, which is exactly the state being discarded.
     */
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

    /** Test/diagnostics hook: admissions a key's rule still has left in the current rolling window. */
    public int availableNow(RateLimitKey key, RateLimitRule rule) {
        synchronized (lock) {
            Instant now = clock.instant();
            Window window = windowFor(key, rule, now);
            window.evictExpired(rule, now);
            return Math.max(0, rule.permits() - window.size);
        }
    }

    /**
     * The admission instants of one (key, window length), as a ring buffer sized to the highest ceiling
     * that has charged it — the rules' own ceilings bound the memory, so a large window costs what it
     * admits and no more. Entries are appended in time order, which is what lets eviction and
     * {@link #timeToFreeSlot} work from the head.
     *
     * <p><strong>Fullness is judged against the caller's rule, not the buffer.</strong> Two rules with the
     * same window share this counter at different ceilings (a background call stops short of the one a
     * search may use; see {@code RateLimitPolicies}), so the count can legitimately sit above a lower
     * ceiling, and the buffer grows when a higher one arrives after a lower one created it.
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

        /** Drop admissions that have aged out of the trailing window; they no longer count. */
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
         * When enough admissions age out to put the count back under {@code rule}'s ceiling — the first
         * instant this window admits that rule again. That is the oldest admission only when the count
         * is exactly at the ceiling; above it (a higher ceiling on the same counter spent past this one)
         * it is the one {@code size - permits} further in, and waiting on the oldest would wake the
         * caller to a window that is still full. Always positive: only reached while full, and
         * {@link #evictExpired} has just dropped everything at or before the cutoff.
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

        /** Re-lay the ring from index 0 into a larger buffer, preserving time order. */
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
