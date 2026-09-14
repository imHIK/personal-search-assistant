package io.personalassistant.testsupport;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.common.ratelimit.RateLimiter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link RateLimiter} that admits everything and remembers what it was asked, so a test can assert on
 * the quota a call was charged to — and, by setting {@link #failWith}, on how a caller reacts to being
 * throttled without needing a real bucket to run dry.
 */
public class RecordingRateLimiter implements RateLimiter {

    public final List<RateLimit> acquired = new ArrayList<>();
    public final Map<String, Instant> penalties = new LinkedHashMap<>();

    /** When set, every {@code acquire} throws this instead of admitting the call. */
    public RateLimitedException failWith;

    @Override
    public void acquire(RateLimit limit) {
        acquired.add(limit);
        if (failWith != null) {
            throw failWith;
        }
    }

    @Override
    public void penalize(RateLimitKey key, Instant until) {
        penalties.put(key.value(), until);
    }
}
