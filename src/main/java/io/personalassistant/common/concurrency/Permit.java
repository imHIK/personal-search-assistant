package io.personalassistant.common.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A slot held in every one of {@code scopeKeys} at once, until {@code expiresAt}; renewing extends by
 * {@code ttl}. It must outlive one guarded unit of work, since it is only renewed between units.
 */
public record Permit(String id, String owner, List<String> scopeKeys, Duration ttl, Instant expiresAt) {
}
