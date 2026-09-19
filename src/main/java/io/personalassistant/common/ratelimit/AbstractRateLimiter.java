package io.personalassistant.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The half of admission control that does not care where the counters live: how long a caller may
 * block, what fail-fast means, and how far a server's {@code Retry-After} is trusted. A store supplies
 * only {@link #reserve} and {@link #storePenalty}.
 *
 * <p>Split out when the Redis store arrived, so the two stores cannot drift on the behaviour callers
 * depend on. {@code IngestionRunner} and {@code IndexingRunner} persist the {@code retryAt} this loop
 * computes as the instant deferred work reopens; a store that waited or clamped differently would move
 * that instant. {@code SlidingWindowRateLimiterTest} drives this loop directly, which is what covers the
 * Redis store's behaviour apart from its script.
 *
 * <p>Sleeping happens outside any store lock and the ceilings are re-checked afterwards, because between
 * waking and re-reserving another caller may have taken the slot that was waited for.
 */
abstract class AbstractRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(AbstractRateLimiter.class.getName());

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

    /**
     * Either records one admission in every window of {@code limit} and returns {@link Duration#ZERO},
     * or records nothing and returns how long the caller must wait — including any penalty on the key.
     *
     * <p><strong>Acquisition must be all-or-nothing across rules.</strong> A call that could pay the
     * per-second ceiling but not the daily one spends nothing, so it cannot deplete the short window
     * while stalled on the long one. An unlimited policy still respects a penalty: the server's own
     * answer about its capacity outranks our absent guess about it.
     *
     * @param now the caller's clock; a store with its own authoritative clock may ignore it
     */
    abstract Duration reserve(RateLimit limit, Instant now);

    /**
     * Pause {@code key} until {@code until}, already clamped. Must keep the furthest-out instant: two
     * concurrent 429s must not shorten each other's backoff.
     */
    abstract void storePenalty(RateLimitKey key, Instant until);

    @Override
    public void acquire(RateLimit limit) {
        if (limit == null) {
            return;
        }
        Duration budget = Duration.ofSeconds(maxWaitSeconds);
        Duration waited = Duration.ZERO;
        while (true) {
            Instant now = clock.instant();
            Duration waitFor = reserve(limit, now);
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
     * here rather than in a caller or a store so one bound covers the in-process pause, cursors and
     * entities; if the server really did mean longer, the next call simply earns another 429 and another
     * pause.
     */
    @Override
    public void penalize(RateLimitKey key, Instant until) {
        if (key == null || until == null) {
            return;
        }
        Instant capped = clock.instant().plusSeconds(maxPenaltySeconds);
        Instant pauseUntil = until.isAfter(capped) ? capped : until;
        storePenalty(key, pauseUntil);
        LOG.log(Level.WARNING, () -> "Rate limited by the server on " + key
                + "; pausing until " + pauseUntil);
    }
}
