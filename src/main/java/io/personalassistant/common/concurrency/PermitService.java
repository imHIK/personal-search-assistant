package io.personalassistant.common.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Leased, TTL-bound concurrency permits scoped global / connector / knowledge. The caller sizes the TTL to
 * the work it guards.
 */
public interface PermitService {

    /** @return the permit, or empty if the scope is at capacity */
    Optional<Permit> tryAcquire(String scopeKey, int max, String owner, Duration ttl);

    /**
     * All-or-nothing across scopes.
     *
     * @return the permit, or empty if any scope is at capacity
     */
    Optional<Permit> tryAcquire(List<ScopeLimit> limits, String owner, Duration ttl);

    /** Extends the expiry by the permit's own ttl. */
    void renew(Permit permit);

    /** Idempotent. */
    void release(Permit permit);
}
