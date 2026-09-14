package io.personalassistant.domain.model.search;

import java.util.Map;

/**
 * A single retrieved chunk with its relevance score and citation info.
 *
 * <p>{@code text} and {@code snippet} are deliberately separate. {@code text} is the chunk exactly as
 * indexed and is what grounding must be built from; {@code snippet} is a display excerpt, short enough
 * for a result card. Collapsing the two loses data irreversibly at the adapter boundary: the agent was
 * previously handed snippets and so answered from ~28% of each chunk, truncating lists mid-item and then
 * reporting the missing rows as absent from the source.
 *
 * @param chunkId     the matched chunk
 * @param entityId    owning entity (for grouping / dedup)
 * @param knowledgeId owning knowledge
 * @param ordinal     position of this chunk within its entity — orders multiple hits from one document,
 *                    and is what a future expansion tool needs to ask for neighbouring chunks
 * @param title       entity title for display
 * @param text        the full chunk text as indexed; the grounding set for answering
 * @param snippet     short display excerpt (highlight fragment when available, else the head of the text)
 * @param uri         locator so the user can open the original
 * @param score       fused relevance score (post-rerank if reranking is on)
 * @param metadata    facets carried through for display/filtering
 */
public record SearchHit(
        String chunkId,
        String entityId,
        String knowledgeId,
        int ordinal,
        String title,
        String text,
        String snippet,
        String uri,
        double score,
        Map<String, Object> metadata) {

    /** Returns a copy of this hit with a new fused score. */
    public SearchHit withScore(double newScore) {
        return new SearchHit(chunkId, entityId, knowledgeId, ordinal, title, text, snippet, uri,
                newScore, metadata);
    }
}
