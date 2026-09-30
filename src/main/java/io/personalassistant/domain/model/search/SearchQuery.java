package io.personalassistant.domain.model.search;

import java.util.List;
import java.util.Map;

/**
 * A parsed search request flowing through the read path.
 *
 * @param text         the natural-language query
 * @param knowledgeIds restrict to these knowledge sources (empty = all)
 * @param filters      filters keyed by the full index field path; this may be a top-level keyword
 *                     field ("sourceType", "uri") or a nested metadata field ("metadata.author").
 *                     A scalar value is an exact term match. A {@code Map} value carrying any of
 *                     {@code gte}/{@code gt}/{@code lte}/{@code lt} becomes a range instead, which is
 *                     how "posted in the last week" or "pays at least X" are expressed
 * @param topK         number of final results to return
 * @param mode         retrieval strategy
 * @param answer       whether to run the agent and synthesize a grounded answer
 * @param maxChunksPerEntity cap on how many chunks one entity may contribute, or null to inherit
 *                     {@code app.search.max-chunks-per-entity}. Set to 1 for "one result per
 *                     document" — the right shape when each entity is a self-contained record (a job
 *                     posting) rather than a long document whose answer legitimately spans chunks
 * @param collapseDuplicates whether to group near-identical results and keep one per group. Off by
 *                     default so existing callers see unchanged behaviour
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

    /** A copy retrieving by {@code other} — how a search whose query vector was refused stays lexical. */
    public SearchQuery withMode(Mode other) {
        return new SearchQuery(text, knowledgeIds, filters, topK, other, answer, maxChunksPerEntity,
                collapseDuplicates);
    }
}
