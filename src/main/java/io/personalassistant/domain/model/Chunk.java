package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
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
 * @param iterableId owning sub-stream, denormalized so chunks can be bulk-deleted by iterable
 *                   when it is removed at the source (mirrors {@code Entity.iterableId})
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
        String iterableId,
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
        return new Chunk(id, entityId, knowledgeId, iterableId, sourceType, ordinal, text,
                tokenCount, embedding, title, uri, metadata);
    }

    /**
     * The string to embed: the chunk body prefixed with whatever context {@code fields} name.
     *
     * <p><strong>Why the embedded string differs from the stored one.</strong> Only {@code text} was ever
     * embedded, so a chunk's title was invisible to the vector leg entirely — a chunk holding nothing but
     * table rows ({@code "17 Dussehra 20/10/2026 Tuesday"}) has no term and no semantic signal linking it
     * to a document called "public_holidays_2026", so it never surfaces for a query about holidays even
     * though it is exactly what the user asked for. Prefixing the title and any structural locator gives
     * every chunk that link.
     *
     * <p>The field list is configuration rather than a fixed rule so enriching the vector with a new
     * facet later is a config edit. {@code title} and {@code uri} resolve against the chunk itself;
     * anything else is looked up in {@link #metadata()} (so {@code sheet}, {@code headingPath},
     * {@code rowRange} work as soon as a chunking strategy supplies them). Unknown or absent names are
     * skipped silently — a field that only some formats produce should not become a hole in the text.
     *
     * <p>Only the embedding input changes. {@code text} stays the exact indexed and displayed body, so
     * BM25, snippets and the answer's grounding are unaffected. Changing the list means re-indexing:
     * existing vectors were built from a different string.
     *
     * @param fields ordered context field names; empty or null embeds the body alone (previous behaviour)
     */
    public String embedText(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return text;
        }
        StringBuilder prefix = new StringBuilder();
        for (String field : fields) {
            String value = contextValue(field);
            if (value != null && !value.isBlank()) {
                prefix.append(field).append(": ").append(value.strip()).append('\n');
            }
        }
        return prefix.isEmpty() ? text : prefix.append('\n').append(text).toString();
    }

    private String contextValue(String field) {
        return switch (field) {
            case "title" -> title;
            case "uri" -> uri;
            default -> {
                Object value = metadata == null ? null : metadata.get(field);
                yield value == null ? null : String.valueOf(value);
            }
        };
    }
}
