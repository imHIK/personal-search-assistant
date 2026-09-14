package io.personalassistant.common.ratelimit;

/**
 * Everything the limiter needs for one call, bundled so it can travel as a single argument through the
 * HTTP layer: which bucket, what ceilings, and what to do when they are hit.
 *
 * <p>The caller supplies the policy on every call and the limiter stores none — the same division
 * {@code PermitService} makes, where the caller owns {@code max} and {@code ttl}. That is what keeps the
 * limiter free of any repository or config dependency, and it means editing an account's limits takes
 * effect on the next grab with no cache to invalidate.
 *
 * @param key    the bucket to charge
 * @param policy the ceilings to satisfy; may be unlimited
 * @param mode   whether to wait or fail when the ceilings are reached
 */
public record RateLimit(RateLimitKey key, RateLimitPolicy policy, RateLimitMode mode) {

    /**
     * An unthrottled call. Distinct from an unlimited <em>policy</em> on a real key: that still honours a
     * {@code Retry-After} the server sent, whereas this is not charged to any bucket at all. Use it for
     * calls with no meaningful quota owner.
     */
    public static final RateLimit NONE =
            new RateLimit(new RateLimitKey("none"), RateLimitPolicy.UNLIMITED, RateLimitMode.FAIL_FAST);

    public RateLimit {
        if (key == null) {
            throw new IllegalArgumentException("rate limit key is required");
        }
        policy = policy == null ? RateLimitPolicy.UNLIMITED : policy;
        mode = mode == null ? RateLimitMode.FAIL_FAST : mode;
    }

    public static RateLimit of(RateLimitKey key, RateLimitPolicy policy, RateLimitMode mode) {
        return new RateLimit(key, policy, mode);
    }

    /** Same bucket and ceilings, different behaviour on breach — the query vs backfill split. */
    public RateLimit withMode(RateLimitMode newMode) {
        return new RateLimit(key, policy, newMode);
    }
}
