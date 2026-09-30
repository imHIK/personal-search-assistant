package io.personalassistant.common.ratelimit;

import java.time.Instant;

/** Holds only counters: every ceiling arrives with the call. */
public interface RateLimiter {

    /** @throws RateLimitedException when the call cannot be admitted within the allowed wait */
    void acquire(RateLimit limit);

    /**
     * Until {@code until}, every call on {@code key} is over its limit, including calls with no configured
     * policy.
     */
    void penalize(RateLimitKey key, Instant until);
}
