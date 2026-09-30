package io.personalassistant.domain.model.search;

import java.util.List;
import java.util.Map;

/**
 * @param filters keyed by index field path; a scalar is an exact term, a Map with gte/gt/lte/lt a range
 * @param maxChunksPerEntity null inherits {@code app.search.max-chunks-per-entity}; 1 means one result per
 *                           document
 * @param collapseDuplicates off by default
 */
public record SearchQuery(
        String text,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        int topK,
        Mode mode,
        boolean answer,
        Integer maxChunksPerEntity,
        boolean collapseDuplicates) {

    public enum Mode { LEXICAL, SEMANTIC, HYBRID }

    public static SearchQuery of(String text) {
        return new SearchQuery(text, List.of(), Map.of(), 10, Mode.HYBRID, false, null, false);
    }

    public SearchQuery withMode(Mode other) {
        return new SearchQuery(text, knowledgeIds, filters, topK, other, answer, maxChunksPerEntity,
                collapseDuplicates);
    }
}
