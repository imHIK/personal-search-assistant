package io.personalassistant.domain.model.enums;

/**
 * Lifecycle of an {@link io.personalassistant.domain.model.Entity} through the indexing
 * stage. Anything in {@code INGESTED} (or with {@code needsReindex=true}) and {@code DELETED}
 * is, in effect, the indexing work queue.
 */
public enum EntityStatus {
    /** Persisted from the source; awaiting indexing. */
    INGESTED,
    /** Claimed by an indexing worker (leased). */
    INDEXING,
    /** Chunks written to the search index; up to date. */
    INDEXED,
    /** Indexing failed past the retry limit; captured error on the entity. */
    FAILED,
    /** Tombstoned at the source; its chunks must be removed from the search index. */
    DELETED
}
