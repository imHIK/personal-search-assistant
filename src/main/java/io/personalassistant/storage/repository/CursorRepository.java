package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.enums.CursorStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The lease and claim methods must be atomic find-and-modify operations, so two workers never run one cursor.
 */
public interface CursorRepository {

    /**
     * Idempotent on the deterministic cursor id.
     *
     * @return true if a new cursor was inserted
     */
    boolean insertIfAbsent(Cursor cursor);

    Optional<Cursor> findById(String id);

    List<Cursor> findByKnowledge(String knowledgeId);

    /**
     * AVAILABLE with no live lease, RATE_LIMITED past its nextAttemptAt, or IN_PROGRESS with an expired
     * lease, among {@code knowledgeIds}, least-recently-run first. Advisory: the real claim is atomic. The
     * caller names the eligible knowledges because a skipped cursor never advances lastRunAt and would starve
     * every other source. An empty collection returns nothing.
     */
    List<Cursor> findClaimable(Collection<String> knowledgeIds, int limit);

    /**
     * Succeeds only while still claimable on findClaimable's terms.
     *
     * @return empty if another worker won the race
     */
    Optional<Cursor> claim(String cursorId, String owner, Duration leaseDuration);

    /** Heartbeat: extends the lease of a cursor this worker still holds. */
    void renewLease(String cursorId, String owner, Instant newExpiry);

    /**
     * Position, fetched stats and lease expiry in one write. Fenced on owner and a live lease.
     *
     * @return false if the lease was lost: the caller must stop touching this cursor
     */
    boolean advancePosition(String cursorId, String owner, CursorPosition position,
                            long fetchedDelta, Instant lastRunAt, Instant newExpiry);

    /**
     * Rests the cursor and clears the lease. Fenced on owner and a live lease.
     *
     * @return false if the lease was lost: the caller must stop touching this cursor
     */
    boolean release(String cursorId, String owner, CursorStatus restingStatus);

    /**
     * Increments retry, stores lastError, clears the lease and rests at restingStatus. Fenced on owner and a
     * live lease.
     *
     * @param nextAttemptAt only RATE_LIMITED carries one; a RATE_LIMITED row with null here is never
     *                      claimable again, so dead-lettering passes null and rests at FAILED
     * @return false if the lease was lost: the caller must stop touching this cursor
     */
    boolean recordFailure(String cursorId, String owner, CursorStatus restingStatus, int retryCount,
                          String lastError, Instant nextAttemptAt);

    /**
     * IDLE to AVAILABLE.
     *
     * @return the number re-armed
     */
    int armForwardCursors(String knowledgeId);

    /**
     * AVAILABLE, IDLE and RATE_LIMITED to SUSPENDED, so a paused knowledge cannot starve others; RATE_LIMITED
     * too, since its hold would lapse mid-pause. IN_PROGRESS is left to the ingestion loop's backstop.
     *
     * @return the number parked
     */
    int suspendByKnowledge(String knowledgeId);

    /**
     * SUSPENDED to AVAILABLE.
     *
     * @return the number re-armed
     */
    int resumeByKnowledge(String knowledgeId);

    /**
     * FAILED to AVAILABLE with the retry streak cleared: the only exit from FAILED. Also revives
     * RATE_LIMITED, clearing nextAttemptAt. Position and attributes are kept, so a cursor resumes where it
     * stopped.
     *
     * @return the number revived
     */
    int retryFailedByKnowledge(String knowledgeId);

    /**
     * A no-op while IN_PROGRESS, so a running lease cannot resurrect it; the next reconcile catches it.
     *
     * @return true if the cursor was retired
     */
    boolean retire(String cursorId);

    /**
     * Back to a fresh AVAILABLE (position, retry and lease reset) with refreshed attributes. A no-op unless
     * RETIRED.
     *
     * @return true if the cursor was revived
     */
    boolean revive(String cursorId, java.util.Map<String, Object> attributes);

    /** Cosmetic, so unfenced and status-agnostic: it touches no field a worker owns. */
    void rename(String cursorId, String iterableName);

    /**
     * Position back to start, AVAILABLE, retry and lease cleared; attributes and stats kept. Skips an
     * IN_PROGRESS cursor so a live lease is not clobbered.
     *
     * @return true if the cursor was reset
     */
    boolean resetToStart(String cursorId);

    void deleteByKnowledge(String knowledgeId);
}
