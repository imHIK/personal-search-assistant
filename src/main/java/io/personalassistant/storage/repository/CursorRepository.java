package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.CursorStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the first-class {@code cursors} collection. The lease/claim methods are
 * the concurrency-critical seam: they must be implemented as atomic find-and-modify operations
 * so two workers never run the same cursor.
 */
public interface CursorRepository {

    /** Insert if absent (idempotent on the deterministic cursor id); no-op if it already exists. */
    void insertIfAbsent(Cursor cursor);

    Optional<Cursor> findById(String id);

    List<Cursor> findByKnowledge(String knowledgeId);

    /**
     * Candidate cursors the ingestion loop may attempt to claim: {@code AVAILABLE} with no live
     * lease. Returned candidates are advisory — the actual claim is atomic via {@link #claim}.
     */
    List<Cursor> findClaimable(int limit);

    /**
     * Atomically lease a cursor: only succeeds if it is still {@code AVAILABLE} (or its previous
     * lease has expired). On success the cursor flips to {@code IN_PROGRESS} with a fresh lease.
     *
     * @return the leased cursor, or empty if another worker won the race
     */
    Optional<Cursor> claim(String cursorId, String owner, Duration leaseDuration);

    /** Heartbeat: extend the lease of a cursor this worker still holds. */
    void renewLease(String cursorId, String owner, Instant newExpiry);

    /** Persist progress mid-lease: advance {@code position} and bump fetched stats. */
    void advancePosition(String cursorId, String position, long fetchedDelta, Instant lastRunAt);

    /** Release the lease and set a resting status ({@code AVAILABLE}/{@code IDLE}/{@code EXHAUSTED}). */
    void release(String cursorId, CursorStatus restingStatus);

    /**
     * Record a failed run: increment retry, clear the lease, and rest at {@code restingStatus}
     * ({@code AVAILABLE} to retry, or {@code FAILED} once the retry budget is spent).
     */
    void recordFailure(String cursorId, CursorStatus restingStatus, int retryCount);

    /**
     * Re-arm a knowledge's forward cursors: flip {@code IDLE → AVAILABLE}. This is the only
     * forward-specific operation; the normal loop then re-picks them like any other cursor.
     *
     * @return the number of cursors re-armed
     */
    int armForwardCursors(String knowledgeId);

    void deleteByKnowledge(String knowledgeId);
}
