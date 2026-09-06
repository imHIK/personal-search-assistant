package io.personalassistant.common.ratelimit;

import java.time.Instant;

/**
 * Admission control for outbound calls. The counterpart to {@code concurrency.PermitService}: permits cap
 * how many calls run <em>at once</em>, this caps how many start <em>per unit of time</em>. They are
 * orthogonal and a call may pass through both.
 *
 * <p>Implementations hold only counters. Every ceiling arrives with the call, so this port never reads
 * configuration or storage.
 */
public interface RateLimiter {

    /**
     * Charge one request to {@code limit}'s bucket, waiting if the limit's mode allows it.
     *
     * @param limit the bucket, ceilings and breach behaviour; {@link RateLimit#NONE} returns immediately
     * @throws RateLimitedException when the call cannot be admitted within the allowed wait
     */
    void acquire(RateLimit limit);

    /**
     * Record that the server itself asked us to back off — a {@code 429} carrying {@code Retry-After}.
     * Until {@code until} passes, every call on {@code key} is treated as over its limit, <em>including
     * calls with no configured policy</em>: a server's own answer about its capacity outranks our absent
     * guess about it.
     *
     * @param key   the bucket the throttled call was charged to
     * @param until the instant the server said to resume
     */
    void penalize(RateLimitKey key, Instant until);
}
