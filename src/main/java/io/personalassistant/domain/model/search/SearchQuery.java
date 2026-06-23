package io.personalassistant.domain.model.search;

import java.util.List;
import java.util.Map;

/**
 * A parsed search request flowing through the read path.
 *
 * @param text         the natural-language query
 * @param knowledgeIds restrict to these knowledge sources (empty = all)
 * @param filters      exact-match facet filters (author, labels…)
 * @param topK         number of final results to return
 * @param mode         retrieval strategy
 * @param answer       whether to run the agent and synthesize a grounded answer
 */
public record SearchQuery(
        String text,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        int topK,
        Mode mode,
        boolean answer) {

    public enum Mode { LEXICAL, SEMANTIC, HYBRID }

    public static SearchQuery of(String text) {
        return new SearchQuery(text, List.of(), Map.of(), 10, Mode.HYBRID, false);
    }
}
