package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;

/**
 * Lives only in OpenSearch and is always regenerable from its entity.
 *
 * @param id {@code {entityId}_{ordinal}}, so re-indexing overwrites
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

    public Chunk withEmbedding(Embedding embedding) {
        return new Chunk(id, entityId, knowledgeId, iterableId, sourceType, ordinal, text,
                tokenCount, embedding, title, uri, metadata);
    }

    /**
     * The string to embed: the body prefixed with the context {@code fields}. Only the embedding input
     * changes; {@code text} stays the indexed and displayed body. Changing the field list needs a re-index.
     * Title and uri resolve on the chunk, other names in its metadata, and absent ones are skipped.
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
