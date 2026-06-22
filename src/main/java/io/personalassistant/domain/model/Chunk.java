package io.personalassistant.domain.model;

import java.util.Map;

/**
 * A unit of text derived from a {@code Document}: the thing that gets embedded,
 * indexed, retrieved, and ranked.
 *
 * @param id         derived id "{documentId}_{ordinal}" for idempotent re-indexing
 * @param documentId owning document
 * @param sourceId   denormalized for fast filtering and cascade deletes
 * @param ordinal    position within the document
 * @param text       the chunk text
 * @param tokenCount approximate token length
 * @param embedding  vector representation (nullable until embedded)
 * @param metadata   chunk-level facets (page, heading…)
 */
public record Chunk(
        String id,
        String documentId,
        String sourceId,
        int ordinal,
        String text,
        int tokenCount,
        Embedding embedding,
        Map<String, Object> metadata) {}
