package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.Map;

/**
 * A first-class ingestion position: <em>position + lease + status</em>, not just a position.
 * Exactly one cursor exists per {@code (knowledgeId, iterableId, direction)}. Persisted in
 * the Mongo {@code cursors} collection.
 *
 * <p>The ingestion job leases a cursor (atomic find-and-modify to {@link CursorStatus#IN_PROGRESS}),
 * pages from {@link #position}, advances it after each page, and finally sets a resting status
 * ({@code AVAILABLE} / {@code IDLE} / {@code EXHAUSTED} / {@code FAILED}).
 *
 * <p>A cursor is <strong>self-contained</strong>: it snapshots the {@code attributes} of its
 * {@code SourceIterable} at creation time (when {@code discover} runs) so the ingestion runner can
 * rebuild the iterable and call {@code grab} without re-discovering. This matters for API-backed
 * sources (e.g. Slack) where {@code discover} enumerates every channel — paying that on every lease
 * would be quadratic. New iterables are still picked up by the periodic discovery/reconcile pass.
 *
 * @param id          stable id, e.g. {@code "cur_..."}
 * @param knowledgeId owning knowledge
 * @param iterableId  identifies the sub-stream (a channel, folder, label…)
 * @param attributes  connector-specific iterable attributes snapshotted from {@code discover} (the
 *                    {@code grab} inputs, e.g. a folder path); empty for legacy cursors
 * @param direction   backward (backfill) or forward (incremental)
 * @param position    source-defined pagination state (page token, offset, timestamp+id, ...)
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

    /** Lease held by a worker while the cursor is {@code IN_PROGRESS}. */
    public record Lease(String owner, Instant expiresAt) {
        public boolean isLiveAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }

    /**
     * Retry bookkeeping for transient ingestion failures. {@code lastError} captures a compact
     * summary of the most recent failure (the full stack trace goes to the log) so a stuck or
     * {@code FAILED} cursor can be debugged straight from the stored record.
     */
    public record Retry(int count, String lastError) {
        public static Retry zero() {
            return new Retry(0, null);
        }

        public Retry increment() {
            return new Retry(count + 1, lastError);
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
