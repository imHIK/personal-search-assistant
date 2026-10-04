package io.personalassistant.domain.model.enums;

/**
 * Claimable: AVAILABLE, a lease-expired IN_PROGRESS, and RATE_LIMITED once {@code retry.nextAttemptAt} has
 * passed.
 */
public enum CursorStatus {
    AVAILABLE,
    IN_PROGRESS,
    /** A forward cursor that has caught up; rests here until its schedule re-arms it. */
    IDLE,
    /** Parked while its knowledge is paused, so it cannot starve active knowledge in the claim batch. */
    SUSPENDED,
    /** A backward cursor that has drained all history. Terminal. */
    EXHAUSTED,
    /**
     * Its iterable was deleted at the source and its data purged; reconcile revives it if the iterable
     * reappears.
     */
    RETIRED,
    /**
     * Held out of claiming until {@code retry.nextAttemptAt}. A resting state, not a failure; separate from
     * AVAILABLE so the console can say so.
     */
    RATE_LIMITED,
    /** Dead-letter: errored past the retry limit. */
    FAILED
}
