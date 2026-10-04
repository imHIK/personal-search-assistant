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
}
