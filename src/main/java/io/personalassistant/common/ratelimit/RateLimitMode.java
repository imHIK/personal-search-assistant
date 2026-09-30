package io.personalassistant.common.ratelimit;

/**
 * A property of the call path, not the account: one credential embeds a backfill (wait) and a search query
 * (fail fast).
 */
public enum RateLimitMode {

    /**
     * Waits up to {@code app.ratelimit.max-wait-seconds}; beyond that throws RateLimitedException, whose
     * retryAt the runners persist to resume the work later.
     */
    WAIT,

    /** Never sleeps: the caller degrades instead. */
    FAIL_FAST
}
