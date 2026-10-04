package io.personalassistant.common.ratelimit;

import java.time.Duration;

/**
 * Enforced as a rolling window: {@code 500/1m} allows 500 back-to-back calls on an idle key, then nothing
 * until they age out. Permits return as a block rather than dripping back (see SlidingWindowRateLimiter).
 * Whole seconds, so it serializes as {@code {permits, windowSeconds}}.
 */
public record RateLimitRule(int permits, long windowSeconds) {

    public RateLimitRule {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive, got " + permits);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive, got " + windowSeconds);
        }
    }

    public static RateLimitRule of(int permits, Duration window) {
        if (window == null) {
            throw new IllegalArgumentException("window is required");
        }
        return new RateLimitRule(permits, window.toSeconds());
    }

    public Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    /** The compact form {@link RateLimitRules} parses, e.g. {@code "500/1m"}. */
    @Override
    public String toString() {
        return permits + "/" + RateLimitRules.format(window());
    }
}
