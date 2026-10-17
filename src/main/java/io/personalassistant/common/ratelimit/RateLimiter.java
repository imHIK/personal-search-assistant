package io.personalassistant.common.ratelimit;

import java.time.Instant;

/** Holds only counters: every ceiling arrives with the call. */
public interface RateLimiter {

    /** @throws RateLimitedException when the call cannot be admitted within the allowed wait */
    void acquire(RateLimit limit);

    /**
     * Until {@code until}, every call on {@code key} is over its limit, including calls with no configured
     * policy.
     *
     * @return the instant actually applied, after any clamping; {@code until} when there is no key
     */
    Instant penalize(RateLimitKey key, Instant until);

    /**
     * Drops every window on {@code key}, whatever rule widths recorded it, and any pause. Idempotent. Only
     * the local count: a provider that has not reset its own will answer with a 429 and pause the key again.
     */
    void reset(RateLimitKey key);
}
