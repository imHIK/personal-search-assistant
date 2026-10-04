package io.personalassistant.common.concurrency;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acquisition is all-or-nothing across scopes, under one lock; expired permits are reclaimed like a Redis
 * TTL. Single-node only.
 */
@ApplicationScoped
public class InMemoryPermitService implements PermitService {

    /** scopeKey → (permitId → expiry) */
    private final Map<String, Map<String, Instant>> scopes = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @Override
    public Optional<Permit> tryAcquire(String scopeKey, int max, String owner, Duration ttl) {
        return tryAcquire(List.of(new ScopeLimit(scopeKey, max)), owner, ttl);
    }

    @Override
    public Optional<Permit> tryAcquire(List<ScopeLimit> limits, String owner, Duration ttl) {
        if (limits == null || limits.isEmpty()) {
            throw new IllegalArgumentException("at least one scope limit is required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);
        String permitId = UUID.randomUUID().toString();

        synchronized (lock) {
            for (ScopeLimit limit : limits) {
                Map<String, Instant> live = scopes.computeIfAbsent(limit.key(), k -> new HashMap<>());
                purgeExpired(live, now);
                if (live.size() >= limit.max()) {
                    return Optional.empty();
                }
            }
            List<String> keys = new ArrayList<>(limits.size());
            for (ScopeLimit limit : limits) {
                scopes.get(limit.key()).put(permitId, expiry);
                keys.add(limit.key());
            }
            return Optional.of(new Permit(permitId, owner, List.copyOf(keys), ttl, expiry));
        }
    }

    @Override
    public void renew(Permit permit) {
        if (permit == null) {
            return;
        }
        Instant expiry = Instant.now().plus(permit.ttl());
        synchronized (lock) {
            for (String key : permit.scopeKeys()) {
                Map<String, Instant> live = scopes.get(key);
                if (live != null && live.containsKey(permit.id())) {
                    live.put(permit.id(), expiry);
                }
            }
        }
    }

    @Override
    public void release(Permit permit) {
        if (permit == null) {
            return;
        }
        synchronized (lock) {
            for (String key : permit.scopeKeys()) {
                Map<String, Instant> live = scopes.get(key);
                if (live != null) {
                    live.remove(permit.id());
                }
            }
        }
    }

    public int liveCount(String scopeKey) {
        synchronized (lock) {
            Map<String, Instant> live = scopes.get(scopeKey);
            if (live == null) {
                return 0;
            }
            purgeExpired(live, Instant.now());
            return live.size();
        }
    }

    private static void purgeExpired(Map<String, Instant> live, Instant now) {
        live.entrySet().removeIf(e -> !e.getValue().isAfter(now));
    }
}
