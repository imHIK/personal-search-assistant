package io.personalassistant.common.concurrency;

import java.util.List;
import java.util.Optional;

/**
 * A reusable concurrency limiter with leased, TTL-bound permits. Limits how many units of
 * work run at once, scoped at multiple levels (global / connector / knowledge) so one source
 * can't starve the rest. Reused by both the ingestion and indexing stages.
 *
 * <p>The interface is storage-agnostic. The default implementation is in-memory; a Redis-backed
 * implementation ({@code SET key val NX PX <ttl>} to acquire, key expiry to auto-reclaim) can
 * be dropped in without changing callers.
 */
public interface PermitService {

    /**
     * Try to acquire a single-scope permit.
     *
     * @return the permit, or empty if the scope is at capacity
     */
    Optional<Permit> tryAcquire(String scopeKey, int max, String owner);

    /**
     * Try to acquire a permit across several scopes atomically (all-or-nothing): the permit is
     * granted only if every scope has free capacity, and it then occupies one slot in each.
     *
     * @return the permit, or empty if any scope is at capacity
     */
    Optional<Permit> tryAcquire(List<ScopeLimit> limits, String owner);

    /** Extend a permit's lease (heartbeat) so a long-running unit of work doesn't lose it. */
    void renew(Permit permit);

    /** Release a permit, freeing a slot in each of its scopes. Idempotent. */
    void release(Permit permit);
}
