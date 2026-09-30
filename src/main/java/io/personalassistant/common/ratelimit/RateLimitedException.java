package io.personalassistant.common.ratelimit;

import java.time.Instant;

/**
 * {@link #retryAt()} is when the limiter knows the call would be admitted; the runners store it as
 * nextAttemptAt, so work resumes when the window reopens.
 */
public class RateLimitedException extends RuntimeException {

    private final transient RateLimitKey key;
    private final Instant retryAt;

    public RateLimitedException(RateLimitKey key, Instant retryAt) {
        super("Rate limit reached for " + key + "; retry at " + retryAt);
        this.key = key;
        this.retryAt = retryAt;
    }

    public RateLimitKey key() {
        return key;
    }

    /** Never null. */
    public Instant retryAt() {
        return retryAt;
    }
}
