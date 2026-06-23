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
    /** A backward cursor that has drained all history (terminal). */
    EXHAUSTED,
    /** Errored past the retry limit; dead-letter, needs intervention. */
    FAILED
}
