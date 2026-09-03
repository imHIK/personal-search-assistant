package io.personalassistant.domain.model.enums;

/**
 * Identifies the kind of data source a {@code Source} represents.
 * Each value corresponds to exactly one {@code SourceConnector} adapter.
 * Add a new constant here when introducing a new integration.
 */
public enum SourceType {
    LOCAL_FS,
    GMAIL,
    SLACK,
    GOOGLE_DRIVE,
    NOTION,
    // Company job boards across every supported applicant-tracking platform (Greenhouse, Lever,
    // Ashby). ONE type, not one per platform: which ATS a company uses is an implementation detail of
    // fetching, resolved per company in JobBoardsConnector.discover and carried on the iterable.
    // Public, unauthenticated and snapshot-shaped — a feed rather than a corpus, so it is the
    // connector that opts into a retention window.
    JOB_BOARDS
}
