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
    NOTION
}
