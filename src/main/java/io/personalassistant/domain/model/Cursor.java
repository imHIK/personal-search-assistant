package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.Map;

/**
 * Exactly one per (knowledgeId, iterableId, direction). Snapshots its iterable's attributes at discovery, so
 * a lease can grab without re-discovering.
 *
 * @param iterableName null on cursors written before names were stored; the reconcile pass refreshes it
 * @param position source-defined pagination state
 */
public record Cursor(
        String id,
        String knowledgeId,
        String iterableId,
        String iterableName,
        Map<String, Object> attributes,
        CursorDirection direction,
        CursorPosition position,
        CursorStatus status,
        Lease lease,
        Retry retry,
        Stats stats,
        Scope scope) {

    public Cursor {
        attributes = attributes == null ? Map.of() : attributes;
    }

    public record Lease(String owner, Instant expiresAt) {
        public boolean isLiveAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }

    /**
     * {@code count} is consecutive failures; success resets it. {@code nextAttemptAt} is when a RATE_LIMITED
     * cursor becomes claimable and is null for any other status, which is why dead-lettering to FAILED clears
     * it.
     */
    public record Retry(int count, String lastError, Instant nextAttemptAt) {
        public static Retry zero() {
            return new Retry(0, null, null);
        }

        public Retry increment() {
            return new Retry(count + 1, lastError, nextAttemptAt);
        }
    }

    public record Stats(Instant lastRunAt, long fetched) {
        public static Stats zero() {
            return new Stats(null, 0);
        }
    }

    public record Scope(SourceType connectorType) {}

    public boolean hasLiveLease(Instant now) {
        return lease != null && lease.isLiveAt(now);
    }
}
