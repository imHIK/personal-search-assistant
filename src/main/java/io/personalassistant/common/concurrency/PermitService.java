package io.personalassistant.common.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A reusable concurrency limiter with leased, TTL-bound permits. Limits how many units of
 * work run at once, scoped at multiple levels (global / connector / knowledge) so one source
 * can't starve the rest. Reused by both the ingestion and indexing stages.
 *
 * <p>The TTL is supplied by the caller per acquisition, not owned by the service — each stage
 * sizes it to the work it guards (the permit must outlive a single guarded unit, since it is only
 * renewed between units). This keeps one shared limiter from baking in a single TTL that can't suit
 * both stages at once.
 *
 * <p>The interface is storage-agnostic. The default implementation is in-memory; a Redis-backed
 * implementation ({@code SET key val NX PX <ttl>} to acquire, key expiry to auto-reclaim) can
 * be dropped in without changing callers.
 */
public interface PermitService {

    /**
     * Try to acquire a single-scope permit with the given TTL.
     *
     * @return the permit, or empty if the scope is at capacity
     */
    Optional<Permit> tryAcquire(String scopeKey, int max, String owner, Duration ttl);

    /**
     * Try to acquire a permit across several scopes atomically (all-or-nothing) with the given TTL:
     * the permit is granted only if every scope has free capacity, and it then occupies one slot in
     * each.
     *
     * @return the permit, or empty if any scope is at capacity
     */
    Optional<Permit> tryAcquire(List<ScopeLimit> limits, String owner, Duration ttl);

    /**
     * Extend a permit's lease (heartbeat) so a long-running unit of work doesn't lose it. The
     * expiry is pushed out by the permit's own {@link Permit#ttl()}.
     */
    void renew(Permit permit);

    /** Release a permit, freeing a slot in each of its scopes. Idempotent. */
    void release(Permit permit);
}
