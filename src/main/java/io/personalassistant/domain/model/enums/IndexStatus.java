package io.personalassistant.domain.model.enums;

/**
 * Lifecycle of a document as it moves through the indexing pipeline.
 * Anything not {@code INDEXED} is, in effect, the work queue.
 */
public enum IndexStatus {
    PENDING,
    PARSING,
    CHUNKED,
    INDEXED,
    FAILED
}
