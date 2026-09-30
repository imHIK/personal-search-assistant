package io.personalassistant.common.ratelimit;

public record RateLimit(RateLimitKey key, RateLimitPolicy policy, RateLimitMode mode) {

    /** Charged to no bucket. Unlike an unlimited policy on a real key, it ignores a server's Retry-After. */
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

    public RateLimit withMode(RateLimitMode newMode) {
        return new RateLimit(key, policy, newMode);
    }
}
