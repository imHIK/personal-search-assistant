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

public class RecordingRateLimiter implements RateLimiter {

    public final List<RateLimit> acquired = new ArrayList<>();
    public final Map<String, Instant> penalties = new LinkedHashMap<>();

    public RateLimitedException failWith;

    @Override
    public void acquire(RateLimit limit) {
        acquired.add(limit);
        if (failWith != null) {
            throw failWith;
        }
    }

    @Override
    public Instant penalize(RateLimitKey key, Instant until) {
        penalties.put(key.value(), until);
        return until;
    }
}
