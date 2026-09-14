package io.personalassistant.common.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
 * <p>Sleeping happens outside the lock and the ceilings are re-checked afterwards, because between
 * waking and re-acquiring another thread may have taken the slot that was waited for.
 *
 * <p>Single-node only, the same accepted limitation as permits: the windows are lost on restart, so a
 * restart can admit one extra burst. Swap in a Redis-backed implementation for multi-node — the
 * {@link RateLimiter} contract is unchanged.
 */
@ApplicationScoped
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(SlidingWindowRateLimiter.class.getName());

    /** Window count past which idle entries are swept, so deleted accounts don't accumulate. */
    private static final int PURGE_THRESHOLD = 256;
    private static final Duration PURGE_IDLE_AFTER = Duration.ofHours(1);

    /** {@code "<key>#<windowSeconds>"} → window. One per rule, which is how multi-window composes. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** {@code "<key>"} → the instant a {@code Retry-After} said to resume. */
    private final Map<String, Instant> penalties = new ConcurrentHashMap<>();

    private final Object lock = new Object();

    @ConfigProperty(name = "app.ratelimit.max-wait-seconds", defaultValue = "60")
    long maxWaitSeconds;

    @ConfigProperty(name = "app.ratelimit.max-penalty-seconds", defaultValue = "21600")
    long maxPenaltySeconds;

    /** Package-private seams so tests drive time deterministically instead of sleeping. */
    Clock clock = Clock.systemUTC();

    Waiter waiter = duration -> Thread.sleep(Math.max(1L, duration.toMillis()));

    /** How a caller is made to wait; separated only so tests can advance a fake clock instead. */
    @FunctionalInterface
    interface Waiter {
        void await(Duration duration) throws InterruptedException;
    }

    @Override
    public void acquire(RateLimit limit) {
        if (limit == null) {
            return;
        }
        Duration budget = Duration.ofSeconds(maxWaitSeconds);
        Duration waited = Duration.ZERO;
        while (true) {
            Instant now = clock.instant();
            Duration waitFor;
            synchronized (lock) {
                waitFor = reserve(limit, now);
            }
            if (waitFor.isZero()) {
                return;
            }
            Instant retryAt = now.plus(waitFor);
            if (limit.mode() == RateLimitMode.FAIL_FAST) {
                throw new RateLimitedException(limit.key(), retryAt);
            }
            Duration remaining = budget.minus(waited);
            if (waitFor.compareTo(remaining) > 0) {
                // Longer than a scheduler thread should be held. Hand the caller the reopening instant
                // so it can defer the work durably rather than sleeping through a daily quota.
                LOG.log(Level.FINE, () -> "Deferring " + limit.key() + " until " + retryAt
                        + " (wait " + waitFor + " exceeds " + budget + ")");
                throw new RateLimitedException(limit.key(), retryAt);
            }
            LOG.log(Level.FINE, () -> "Waiting " + waitFor + " for " + limit.key());
            try {
                waiter.await(waitFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RateLimitedException(limit.key(), retryAt);
            }
            waited = waited.plus(waitFor);
        }
    }

    /**
     * <p>The value is clamped to {@code app.ratelimit.max-penalty-seconds}. {@code Retry-After} is
     * remote input — delta-seconds and HTTP-dates are confused for each other in the wild, and this
     * instant is load-bearing now that a rate-limited cursor is held until it passes. The clamp lives
     * here rather than in a caller so one bound covers the in-process pause, cursors and entities; if
     * the server really did mean longer, the next call simply earns another 429 and another pause.
     */
    @Override
    public void penalize(RateLimitKey key, Instant until) {
        if (key == null || until == null) {
            return;
        }
        Instant capped = clock.instant().plusSeconds(maxPenaltySeconds);
        Instant pauseUntil = until.isAfter(capped) ? capped : until;
        // Keep the furthest-out instant: two concurrent 429s must not shorten each other's backoff.
        penalties.merge(key.value(), pauseUntil, (a, b) -> a.isAfter(b) ? a : b);
        LOG.log(Level.WARNING, () -> "Rate limited by the server on " + key
                + "; pausing until " + pauseUntil);
    }

    /**
     * Either records one admission in every window and returns {@link Duration#ZERO}, or records nothing
     * and returns how long the caller must wait.
     *
     * <p><strong>Acquisition is all-or-nothing across rules.</strong> A call that could pay the
     * per-second ceiling but not the daily one spends nothing, so it cannot deplete the short window
     * while stalled on the long one. Called under {@link #lock}.
     */
    private Duration reserve(RateLimit limit, Instant now) {
        Duration wait = penaltyRemaining(limit.key(), now);
        // An unlimited policy still respects a penalty: the server's own answer about its capacity
        // outranks our absent guess about it.
        if (limit.policy().isUnlimited()) {
            return wait;
        }
        for (RateLimitRule rule : limit.policy().rules()) {
            Window window = windowFor(limit.key(), rule, now);
            window.evictExpired(rule, now);
            if (window.isFull()) {
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
            windowFor(limit.key(), rule, now).record(now);
        }
        return Duration.ZERO;
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
            return window.capacity - window.size;
        }
    }

    /**
     * The admission instants of one (key, rule), as a ring buffer holding at most {@code permits} of
     * them — the rule's own ceiling bounds the memory, so a large window costs what it admits and no
     * more. Entries are appended in time order, which is what lets eviction and {@link #timeToFreeSlot}
     * look only at the head.
     */
    private static final class Window {

        private final int capacity;
        private final Instant[] admitted;
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

        private boolean isFull() {
            return size >= capacity;
        }

        /**
         * When the oldest admission ages out — the first instant this window admits again. Always
         * positive: only reached while full, and {@link #evictExpired} has just dropped everything at or
         * before the cutoff, so the head is strictly inside the window.
         */
        private Duration timeToFreeSlot(RateLimitRule rule, Instant now) {
            return Duration.between(now, admitted[head].plus(rule.window()));
        }

        private void record(Instant now) {
            admitted[(head + size) % capacity] = now;
            size++;
            lastTouched = now;
        }
    }
}
