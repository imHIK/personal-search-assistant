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
 * Persistence port for the first-class {@code cursors} collection. The lease/claim methods are
 * the concurrency-critical seam: they must be implemented as atomic find-and-modify operations
 * so two workers never run the same cursor.
 */
public interface CursorRepository {

    /**
     * Insert if absent (idempotent on the deterministic cursor id); no-op if it already exists.
     *
     * @return {@code true} if a new cursor was inserted, {@code false} if it already existed
     */
    boolean insertIfAbsent(Cursor cursor);

    Optional<Cursor> findById(String id);

    List<Cursor> findByKnowledge(String knowledgeId);

    /**
     * Candidate cursors the ingestion loop may attempt to claim: {@code AVAILABLE} with no live
     * lease, a {@code RATE_LIMITED} one whose {@code retry.nextAttemptAt} has passed, or an
     * {@code IN_PROGRESS} one whose lease expired — restricted to cursors of {@code knowledgeIds},
     * least-recently-run first. Returned candidates are advisory — the actual claim is atomic via
     * {@link #claim}.
     *
     * <p>The caller names the eligible knowledges because a cursor the loop skips never advances its
     * {@code lastRunAt}: left in the ordering, it keeps its slot at the head of the bounded batch on
     * every tick, and enough of them starve every other source. An empty collection returns nothing.
     */
    List<Cursor> findClaimable(Collection<String> knowledgeIds, int limit);

    /**
     * Atomically lease a cursor: only succeeds if it is still claimable on the same terms as
     * {@link #findClaimable} (so a {@code RATE_LIMITED} cursor whose hold has not elapsed is
     * refused). On success the cursor flips to {@code IN_PROGRESS} with a fresh lease.
     *
     * @return the leased cursor, or empty if another worker won the race
     */
    Optional<Cursor> claim(String cursorId, String owner, Duration leaseDuration);

    /** Heartbeat: extend the lease of a cursor this worker still holds. */
    void renewLease(String cursorId, String owner, Instant newExpiry);

    /**
     * Persist progress mid-lease and renew the lease in one atomic, <em>lease-fenced</em> write:
     * advance {@code position}, bump fetched stats, and push the lease expiry to {@code newExpiry}.
     * The update only applies if {@code owner} still holds a live lease, so a worker whose lease
     * lapsed (e.g. a single page outran the TTL and another worker re-claimed the cursor) cannot
     * clobber the new owner's state.
     *
     * @return {@code true} if the caller still owned the lease and the write applied; {@code false}
     *         if the lease was lost (the caller should stop touching this cursor)
     */
    boolean advancePosition(String cursorId, String owner, CursorPosition position,
                            long fetchedDelta, Instant lastRunAt, Instant newExpiry);

    /**
     * Lease-fenced release: set a resting status ({@code AVAILABLE}/{@code IDLE}/{@code EXHAUSTED})
     * and clear the lease, only if {@code owner} still holds a live lease.
     *
     * @return {@code true} if the caller still owned the lease and the write applied
     */
    boolean release(String cursorId, String owner, CursorStatus restingStatus);

    /**
     * Lease-fenced failure record: increment retry, store {@code lastError}, clear the lease, and
     * rest at {@code restingStatus} ({@code AVAILABLE} to retry now, {@code RATE_LIMITED} to hold
     * until a quota reopens, or {@code FAILED} once the budget is spent) — only if {@code owner}
     * still holds a live lease.
     *
     * @param lastError     a compact summary of the failure, persisted on the cursor for debugging
     * @param nextAttemptAt when the cursor becomes claimable again, or null for "right away". Only
     *                      {@code RATE_LIMITED} carries one; a {@code RATE_LIMITED} row with a null
     *                      here is never claimable again, so dead-lettering must pass null <em>and</em>
     *                      rest at {@code FAILED}
     * @return {@code true} if the caller still owned the lease and the write applied
     */
    boolean recordFailure(String cursorId, String owner, CursorStatus restingStatus, int retryCount,
                          String lastError, Instant nextAttemptAt);

    /**
     * Re-arm a knowledge's forward cursors: flip {@code IDLE → AVAILABLE}. This is the only
     * forward-specific operation; the normal loop then re-picks them like any other cursor.
     *
     * @return the number of cursors re-armed
     */
    int armForwardCursors(String knowledgeId);

    /**
     * Park a paused knowledge's claimable cursors: flip
     * {@code AVAILABLE}/{@code IDLE}/{@code RATE_LIMITED → SUSPENDED} so they drop out of
     * {@link #findClaimable} and cannot starve active knowledge. A rate-limited cursor is included
     * because its hold elapses on its own — left alone it would rejoin the batch mid-pause. Leased
     * ({@code IN_PROGRESS}) cursors are left alone — they rest at a normal status when their lease
     * ends and are caught by the ingestion loop's backstop.
     *
     * @return the number of cursors parked
     */
    int suspendByKnowledge(String knowledgeId);

    /**
     * Re-arm a resumed knowledge's parked cursors: flip {@code SUSPENDED → AVAILABLE}. Inverse of
     * {@link #suspendByKnowledge}.
     *
     * @return the number of cursors re-armed
     */
    int resumeByKnowledge(String knowledgeId);

    /**
     * Re-arm a knowledge's dead-lettered cursors: flip {@code FAILED → AVAILABLE} and clear the retry
     * streak. This is the <em>only</em> exit from {@code FAILED} — {@link #armForwardCursors} matches
     * only {@code IDLE}, {@link #resumeByKnowledge} only {@code SUSPENDED}, and the claim filter
     * excludes {@code FAILED} — so without it a cursor that exhausted its retries during a transient
     * outage is stranded until someone edits the database by hand.
     *
     * <p>{@code RATE_LIMITED} is revived too, and its {@code nextAttemptAt} cleared. That is the
     * "I have raised the quota, run now" button: nothing else clears a hold early, and the instant
     * was computed against a limit the user has since changed.
     *
     * <p>Position and attributes are kept, so the cursor resumes exactly where it stopped rather than
     * re-walking the source from the beginning.
     *
     * @return the number of cursors revived
     */
    int retryFailedByKnowledge(String knowledgeId);

    /**
     * Retire a cursor whose iterable was deleted at the source: flip it to {@code RETIRED} and clear
     * any lease. A no-op if the cursor is currently {@code IN_PROGRESS} (a worker is mid-run; the
     * next reconcile pass catches it), which also avoids a running lease resurrecting it.
     *
     * @return {@code true} if the cursor was retired
     */
    boolean retire(String cursorId);

    /**
     * Revive a {@code RETIRED} cursor because its iterable reappeared: reset it to a fresh
     * {@code AVAILABLE} state (position back to start, retry cleared, lease cleared) and refresh the
     * snapshotted {@code attributes}. A no-op unless the cursor is currently {@code RETIRED}.
     *
     * @return {@code true} if the cursor was revived
     */
    boolean revive(String cursorId, java.util.Map<String, Object> attributes);

    /**
     * Refresh the snapshotted {@code iterableName} — the label the console shows in place of the raw
     * iterable id. Cosmetic and therefore deliberately unfenced and status-agnostic: it touches no
     * field a worker owns, so it cannot corrupt an in-flight run, and the reconcile pass that calls
     * it re-runs anyway. Also the backfill path for cursors written before names were stored.
     */
    void rename(String cursorId, String iterableName);

    /**
     * Rewind a cursor for a membership re-walk: position back to {@link CursorPosition#start()},
     * status {@code AVAILABLE} (re-arming a backward cursor that had {@code EXHAUSTED} and rewinding
     * a forward one), retry and lease cleared. Snapshotted {@code attributes} and {@code stats} are
     * left intact. Skips a cursor a worker is mid-run on ({@code IN_PROGRESS}) so a live lease is not
     * clobbered. Used by the edit path when the connector's membership signature changed, so the
     * cursor re-covers its full range under the new rule (change-detection then skips unchanged items).
     *
     * @return {@code true} if the cursor was reset
     */
    boolean resetToStart(String cursorId);

    void deleteByKnowledge(String knowledgeId);
}
