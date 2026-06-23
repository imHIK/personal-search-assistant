package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.Map;

/**
 * An indexable unit derived from an {@link Entity} at indexing time: the thing that gets
 * embedded, indexed, retrieved and ranked. Chunks are a <em>derived</em> artifact and live
 * <strong>only in OpenSearch</strong> — never persisted to Mongo (the entity is the source of
 * truth and chunks can always be regenerated). This record therefore carries everything the
 * search index needs (denormalized title/uri/sourceType) so the adapter stays thin.
 *
 * @param id         derived id {@code "{entityId}_{ordinal}"} for idempotent re-indexing
 * @param entityId   owning entity
 * @param knowledgeId owning knowledge (for filtering / cascade deletes / scope)
 * @param sourceType connector type, denormalized for filtering
 * @param ordinal    position within the entity
 * @param text       the chunk text (BM25 field)
 * @param tokenCount approximate token length
 * @param embedding  vector representation (nullable until embedded)
 * @param title      entity title, denormalized for display
 * @param uri        citation locator, denormalized for display
 * @param metadata   chunk-level facets (page, heading…) plus carried entity facets
 */
public record Chunk(
        String id,
        String entityId,
        String knowledgeId,
        SourceType sourceType,
        int ordinal,
        String text,
        int tokenCount,
        Embedding embedding,
        String title,
        String uri,
        Map<String, Object> metadata) {

    /** Returns a copy of this chunk with the given embedding attached. */
    public Chunk withEmbedding(Embedding embedding) {
        return new Chunk(id, entityId, knowledgeId, sourceType, ordinal, text,
                tokenCount, embedding, title, uri, metadata);
    }
}
