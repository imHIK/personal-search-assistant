package io.personalassistant.domain.model.enums;

/**
 * Whether an entity's stored content is durable. Inline text and a LOCAL_FS fileRef are; a staged copy
 * (Drive's scratch dir) is not, so the connector must fetch the bytes again.
 */
public enum ReindexMode {
    REINDEX_ONLY,
    FETCH_AND_REINDEX
}
