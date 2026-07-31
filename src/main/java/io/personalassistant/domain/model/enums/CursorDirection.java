package io.personalassistant.domain.model.enums;

/**
 * Direction a {@link io.personalassistant.domain.model.Cursor} walks a source stream.
 *
 * <p>The ingestion job treats both directions identically; the only difference is what
 * re-arms a cursor once it pauses: a {@code BACKWARD} cursor re-arms itself until history is
 * drained ({@code EXHAUSTED}); a {@code FORWARD} cursor is re-armed by the scheduler/webhook.
 */
public enum CursorDirection {
    /** Walks history older than the knowledge anchor (backfill); terminal when drained. */
    BACKWARD,
    /** Walks items at/after the anchor (incremental); lives forever, re-armed on schedule. */
    FORWARD
}
