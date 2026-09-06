package io.personalassistant.common.ratelimit;

import java.time.Duration;

/**
 * One rate ceiling: at most {@code permits} requests may start within any {@code windowSeconds}.
 *
 * <p>Deliberately a free-form (count, duration) pair rather than a fixed "per minute" number, because
 * the services behind it do not agree on a unit — Gmail publishes a per-second quota, Groq a per-minute
 * one, and Drive a daily one. Several rules compose into a {@link RateLimitPolicy}.
 *
 * <p>The window is held as whole seconds rather than a {@link Duration} so this record serializes as
 * {@code {"permits": 500, "windowSeconds": 60}} in both directions with no bespoke mapper — the console
 * reads and writes it over REST, and Mongo stores it, and neither should have to know about ISO-8601
 * duration strings. Sub-second windows are not expressible, which no real quota needs.
 *
 * <p>There is no separate burst knob: the rule is enforced as a rolling window holding {@code permits}
 * admissions, so {@code 500/1m} allows 500 back-to-back requests on a key idle for a minute and then
 * nothing until those calls age out. Permits return as a block, a window after they were spent, rather
 * than dripping back at the sustained rate — see {@code SlidingWindowRateLimiter} for why the drip is
 * the wrong shape against a service counting the same rolling window.
 *
 * @param permits       maximum number of requests per window; must be positive
 * @param windowSeconds the span the ceiling applies over, in seconds; must be positive
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
