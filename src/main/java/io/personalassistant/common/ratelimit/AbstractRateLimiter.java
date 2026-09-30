package io.personalassistant.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Waiting, fail-fast and the penalty clamp, shared by both stores. Sleeping happens outside any store lock
 * and the ceilings are re-checked afterwards, since another caller may have taken the slot.
 */
abstract class AbstractRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(AbstractRateLimiter.class.getName());

    @ConfigProperty(name = "app.ratelimit.max-wait-seconds", defaultValue = "60")
    long maxWaitSeconds;

    @ConfigProperty(name = "app.ratelimit.max-penalty-seconds", defaultValue = "21600")
    long maxPenaltySeconds;

    Clock clock = Clock.systemUTC();

    Waiter waiter = duration -> Thread.sleep(Math.max(1L, duration.toMillis()));

    @FunctionalInterface
    interface Waiter {
        void await(Duration duration) throws InterruptedException;
    }

    /**
     * Records one admission in every window, or none and returns the wait, penalty included. All-or-nothing,
     * so a call that cannot pay the daily ceiling spends nothing from the per-second one.
     *
     * @param now a store with its own clock may ignore it
     */
    abstract Duration reserve(RateLimit limit, Instant now);

    /** Must keep the furthest-out instant: two concurrent 429s must not shorten each other's backoff. */
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
                // Too long to hold a scheduler thread: the caller defers the work durably to the reopening
                // instant.
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
     * Clamped to {@code app.ratelimit.max-penalty-seconds}: Retry-After is remote input, and one bound here
     * covers the pause, cursors and entities.
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
