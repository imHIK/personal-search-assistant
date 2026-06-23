package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;

/**
 * A first-class ingestion position: <em>position + lease + status</em>, not just a position.
 * Exactly one cursor exists per {@code (knowledgeId, iterableId, direction)}. Persisted in
 * the Mongo {@code cursors} collection.
 *
 * <p>The ingestion job leases a cursor (atomic find-and-modify to {@link CursorStatus#IN_PROGRESS}),
 * pages from {@link #position}, advances it after each page, and finally sets a resting status
 * ({@code AVAILABLE} / {@code IDLE} / {@code EXHAUSTED} / {@code FAILED}).
 *
 * @param id          stable id, e.g. {@code "cur_..."}
 * @param knowledgeId owning knowledge
 * @param iterableId  identifies the sub-stream (a channel, folder, label…)
 * @param direction   backward (backfill) or forward (incremental)
 * @param position    opaque source token (page token / timestamp / change id); null at start
 * @param status      operational state
 * @param lease       current holder + expiry, or null when free
 * @param retry       retry bookkeeping
 * @param stats       last-run bookkeeping
 * @param scope       hints used by the PermitService for scoped throttling
 */
public record Cursor(
        String id,
        String knowledgeId,
        String iterableId,
        CursorDirection direction,
        String position,
        CursorStatus status,
        Lease lease,
        Retry retry,
        Stats stats,
        Scope scope) {

    /** Lease held by a worker while the cursor is {@code IN_PROGRESS}. */
    public record Lease(String owner, Instant expiresAt) {
        public boolean isLiveAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }

    /** Retry bookkeeping for transient ingestion failures. */
    public record Retry(int count) {
        public static Retry zero() {
            return new Retry(0);
        }

        public Retry increment() {
            return new Retry(count + 1);
        }
    }

    /** Last-run statistics. */
    public record Stats(Instant lastRunAt, long fetched) {
        public static Stats zero() {
            return new Stats(null, 0);
        }
    }

    /** Scoping hints consumed by the PermitService (e.g. {@code connector:SLACK}). */
    public record Scope(SourceType connectorType) {}

    /** True when this cursor currently holds a lease that has not yet expired. */
    public boolean hasLiveLease(Instant now) {
        return lease != null && lease.isLiveAt(now);
    }
}
