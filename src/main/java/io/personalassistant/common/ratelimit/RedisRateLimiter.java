package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ProviderImpl;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rolling windows and Retry-After pauses in Redis, so they survive restarts: an in-memory day-long window
 * restarts empty while the provider's count carries on. Each reservation is one script, atomic across
 * processes, reading Redis TIME so skewed clocks agree. No fallback when Redis is down: counting in memory
 * would bring the fresh-allowance bug back unseen.
 */
@ApplicationScoped
@ProviderImpl
public class RedisRateLimiter extends AbstractRateLimiter {

    static final String WINDOW_PREFIX = "rl:w:";
    static final String PENALTY_PREFIX = "rl:p:";

    /**
     * KEYS[1] is the penalty, KEYS[2..] one window per rule; ARGV holds a permits, windowMillis pair per
     * rule, then a member unique to this admission. Returns the millis to wait, or 0 once every window
     * recorded it. The wait runs to the admission that brings the count under this call's ceiling, not the
     * oldest: two ceilings can share one window.
     */
    private static final String RESERVE = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local wait = 0
            local pttl = redis.call('PTTL', KEYS[1])
            if pttl > 0 then wait = pttl end
            local rules = #KEYS - 1
            for i = 1, rules do
              local key = KEYS[i + 1]
              local permits = tonumber(ARGV[2 * i - 1])
              local windowMs = tonumber(ARGV[2 * i])
              redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)
              local count = redis.call('ZCARD', key)
              if count >= permits then
                local freeing = redis.call('ZRANGE', key, count - permits, count - permits, 'WITHSCORES')
                local need = math.floor(tonumber(freeing[2]) + windowMs - now)
                if need > wait then wait = need end
              end
            end
            if wait > 0 or rules == 0 then return wait end
            for i = 1, rules do
              redis.call('ZADD', KEYS[i + 1], now, ARGV[#ARGV])
              redis.call('PEXPIRE', KEYS[i + 1], ARGV[2 * i])
            end
            return 0
            """;

    /**
     * KEYS[1] is the penalty, ARGV[1] its length in millis, ARGV[2] the resume instant. Never shortens a
     * pause already in place.
     */
    private static final String PENALIZE = """
            local ttl = tonumber(ARGV[1])
            if ttl <= 0 then return 0 end
            if redis.call('PTTL', KEYS[1]) >= ttl then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ttl)
            return 1
            """;

    private final RedisDataSource redis;

    @Inject
    public RedisRateLimiter(RedisDataSource redis) {
        this.redis = redis;
    }

    @Override
    Duration reserve(RateLimit limit, Instant now) {
        List<RateLimitRule> rules = limit.policy().rules();
        List<String> args = new ArrayList<>(4 + rules.size() * 3);
        args.add(RESERVE);
        args.add(String.valueOf(1 + rules.size()));
        args.add(penaltyKey(limit.key()));
        for (RateLimitRule rule : rules) {
            args.add(windowKey(limit.key(), rule));
        }
        for (RateLimitRule rule : rules) {
            args.add(String.valueOf(rule.permits()));
            args.add(String.valueOf(rule.window().toMillis()));
        }
        args.add(UUID.randomUUID().toString());
        long waitMillis = redis.execute("EVAL", args.toArray(String[]::new)).toLong();
        return waitMillis <= 0 ? Duration.ZERO : Duration.ofMillis(waitMillis);
    }

    @Override
    void storePenalty(RateLimitKey key, Instant until) {
        long ttlMillis = Duration.between(clock.instant(), until).toMillis();
        redis.execute("EVAL", PENALIZE, "1", penaltyKey(key), String.valueOf(ttlMillis), until.toString());
    }

    static String windowKey(RateLimitKey key, RateLimitRule rule) {
        return WINDOW_PREFIX + key.value() + "#" + rule.windowSeconds();
    }

    static String penaltyKey(RateLimitKey key) {
        return PENALTY_PREFIX + key.value();
    }
}
