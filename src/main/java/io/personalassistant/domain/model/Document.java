package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.IndexStatus;
import java.time.Instant;
import java.util.Map;

/**
 * A canonical ingested item (a file, an email, a message). Source of truth in the
 * Mongo {@code documents} collection; OpenSearch chunks are derived from it.
 *
 * @param id          internal id
 * @param sourceId    owning source
 * @param externalId  natural key within the source (path, message id…), unique per source
 * @param contentType MIME type; drives parser selection
 * @param uri         locator for citations / open-in-source
 * @param text        extracted plain text (may be omitted for very large corpora)
 * @param metadata    normalized + source-specific attributes
 * @param checksum    content hash for change detection
 * @param indexStatus pipeline state
 * @param chunkCount  number of chunks produced
 */
public record Document(
        String id,
        String sourceId,
        String externalId,
        String contentType,
        String title,
        String uri,
        String text,
        Map<String, Object> metadata,
        String checksum,
        IndexStatus indexStatus,
        int chunkCount,
        String error,
        Instant createdAt,
        Instant updatedAt) {}
