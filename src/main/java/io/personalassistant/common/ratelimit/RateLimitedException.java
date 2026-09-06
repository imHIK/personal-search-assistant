package io.personalassistant.common.ratelimit;

import java.time.Instant;

/**
 * Thrown when a call cannot proceed within its allowed wait — either because the caller asked to fail
 * fast, or because the wait is longer than a scheduler thread should be held for.
 *
 * <p>{@link #retryAt()} is the point the limiter knows the call would succeed: when the oldest call in
 * the rolling window ages out, or a {@code Retry-After} the server sent. It is strictly better than the flat
 * {@code app.indexing.backoff-seconds}, so the runners write it onto the entity or cursor as
 * {@code nextAttemptAt}: the work resumes when the window actually reopens rather than five minutes later.
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

    /** When the limiter expects the call to be admitted; never null. */
    public Instant retryAt() {
        return retryAt;
    }
}
