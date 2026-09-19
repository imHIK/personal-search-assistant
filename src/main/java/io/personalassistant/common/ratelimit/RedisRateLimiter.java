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
 * {@link RateLimiter} whose rolling windows and {@code Retry-After} pauses live in Redis, so they outlive
 * the JVM. The shipped store ({@code app.ratelimit.store=redis}).
 *
 * <p><strong>Why it exists.</strong> The in-memory store starts every window empty on a restart, and on
 * every {@code quarkusDev} live reload. For a short window that is one extra burst; for
 * {@code app.ratelimit.embedding.rules=6/1m,60/1d} it is a whole fresh day of allowance the provider
 * never granted, so the calls after a restart are 429s the limiter believed it had avoided.
 *
 * <p><strong>Same semantics as {@link SlidingWindowRateLimiter}</strong>, which documents why the window is
 * rolling rather than a refilling bucket. Each (key, rule) is a sorted set of admission instants in epoch
 * millis; eviction is {@code ZREMRANGEBYSCORE}, the wait is the oldest member ageing out. Everything
 * outside the counting — waiting, fail-fast, deferral, the penalty clamp — is {@link AbstractRateLimiter}
 * and shared.
 *
 * <p><strong>One script per reservation</strong>, so "check every rule, then record in all or none" is
 * atomic across callers and processes, preserving the all-or-nothing rule without a lock. The script reads
 * {@code TIME} from Redis rather than taking the JVM clock, so two processes with skewed clocks still agree
 * on which admissions have aged out; the {@code now} argument is ignored.
 *
 * <p>Idle windows need no sweeper: every write sets {@code PEXPIRE} to the window length, so a key nobody
 * charges disappears once its newest admission could no longer count.
 *
 * <p><strong>No fallback when Redis is down.</strong> The exception propagates like a Mongo outage would.
 * Quietly counting in memory instead would reintroduce the fresh-allowance bug with nothing to show it.
 *
 * <p>No unit test: tests here run without external services. The script is exercised by hand — see
 * {@code docs/limitations.md} L9.
 */
@ApplicationScoped
@ProviderImpl
public class RedisRateLimiter extends AbstractRateLimiter {

    static final String WINDOW_PREFIX = "rl:w:";
    static final String PENALTY_PREFIX = "rl:p:";

    /**
     * {@code KEYS[1]} the key's penalty, {@code KEYS[2..]} one window per rule. {@code ARGV} holds a
     * {@code permits, windowMillis} pair per rule, in the same order, then a unique member for this
     * admission — unique because two admissions in the same millisecond must not collapse into one entry.
     * Returns the milliseconds to wait, or 0 once every window has recorded the admission.
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
              if redis.call('ZCARD', key) >= permits then
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local need = math.floor(tonumber(oldest[2]) + windowMs - now)
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
     * {@code KEYS[1]} the penalty, {@code ARGV[1]} its length in millis, {@code ARGV[2]} the resume instant
     * (stored only so {@code redis-cli GET} is readable). Never shortens a pause already in place.
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

    /** Same shape as the in-memory map key, so a window is recognisable in {@code redis-cli}. */
    static String windowKey(RateLimitKey key, RateLimitRule rule) {
        return WINDOW_PREFIX + key.value() + "#" + rule.windowSeconds();
    }

    static String penaltyKey(RateLimitKey key) {
        return PENALTY_PREFIX + key.value();
    }
}
