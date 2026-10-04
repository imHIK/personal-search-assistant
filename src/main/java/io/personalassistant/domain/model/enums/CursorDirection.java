package io.personalassistant.domain.model.enums;

/**
 * A BACKWARD cursor re-arms itself until history is drained (EXHAUSTED); a FORWARD one is re-armed by the
 * scheduler.
 */
public enum CursorDirection {
    /** Items older than the anchor; terminal when drained. */
    BACKWARD,
    /** Items at or after the anchor; re-armed on schedule forever. */
    FORWARD
}
