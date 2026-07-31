package io.personalassistant.domain.model.search;

import java.util.Map;

/**
 * A single retrieved chunk with its relevance score and citation info.
 *
 * @param chunkId     the matched chunk
 * @param entityId    owning entity (for grouping / dedup)
 * @param knowledgeId owning knowledge
 * @param title       entity title for display
 * @param snippet     highlighted / relevant text excerpt
 * @param uri         locator so the user can open the original
 * @param score       fused relevance score (post-rerank if reranking is on)
 * @param metadata    facets carried through for display/filtering
 */
public record SearchHit(
        String chunkId,
        String entityId,
        String knowledgeId,
        String title,
        String snippet,
        String uri,
        double score,
        Map<String, Object> metadata) {

    /** Returns a copy of this hit with a new fused score. */
    public SearchHit withScore(double newScore) {
        return new SearchHit(chunkId, entityId, knowledgeId, title, snippet, uri, newScore, metadata);
    }
}
