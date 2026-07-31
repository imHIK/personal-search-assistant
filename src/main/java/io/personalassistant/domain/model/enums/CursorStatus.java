package io.personalassistant.domain.model.enums;

/**
 * Operational state of a {@link io.personalassistant.domain.model.Cursor}.
 *
 * <p>The ingestion loop only ever asks one question: is this cursor {@link #AVAILABLE}?
 * Everything other than {@link #AVAILABLE}/{@link #IN_PROGRESS} simply means "don't pick me".
 */
public enum CursorStatus {
    /** Re-pick me: just created, more pages remain, or re-armed by the scheduler. */
    AVAILABLE,
    /** Leased and running right now. */
    IN_PROGRESS,
    /** A forward cursor that has caught up; rests here until its schedule re-arms it. */
    IDLE,
    /**
     * Parked because the owning knowledge is paused: excluded from claiming so a paused
     * knowledge's cursors cannot starve active knowledge within the bounded claim batch.
     * Re-armed to {@link #AVAILABLE} when the knowledge resumes.
     */
    SUSPENDED,
    /** A backward cursor that has drained all history (terminal). */
    EXHAUSTED,
    /**
     * The iterable this cursor paged was deleted at the source, so its indexed data has been
     * purged and the cursor is parked here — distinct from {@link #EXHAUSTED}, which means a
     * backfill finished normally. Reconcile revives it to {@link #AVAILABLE} if the iterable
     * reappears.
     */
    RETIRED,
    /** Errored past the retry limit; dead-letter, needs intervention. */
    FAILED
}
