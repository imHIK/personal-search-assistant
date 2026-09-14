package io.personalassistant.domain.model.enums;

/**
 * What a re-index has to do to reach the source content again, declared per connector by
 * {@code SourceConnector#defaultReindexMode()}.
 *
 * <p>The distinction is whether the entity's stored content is durable. Inline text lives in Mongo
 * and a {@code LOCAL_FS} {@code fileRef} points at the user's own file, so both survive as long as
 * the entity does. A staged copy — Drive downloads its bytes into a scratch dir — does not: the OS
 * is entitled to empty that directory, and the connector must be asked for the bytes again rather
 * than the indexer trusting a path it cannot vouch for.
 */
public enum ReindexMode {
    /** Stored content is durable; re-index straight from what the entity already carries. */
    REINDEX_ONLY,
    /** Stored content is a disposable copy; re-fetch from the source before re-indexing. */
    FETCH_AND_REINDEX
}
