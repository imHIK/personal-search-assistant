package io.personalassistant.domain.model.enums;

/** INGESTED (or needsReindex) and DELETED entities are the indexing work queue. */
public enum EntityStatus {
    INGESTED,
    INDEXING,
    INDEXED,
    /** Dead-letter: failed past the retry limit. */
    FAILED,
    /** Tombstoned; its chunks must be removed from the index. */
    DELETED
}
