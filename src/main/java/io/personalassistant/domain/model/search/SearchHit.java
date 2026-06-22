package io.personalassistant.domain.model.search;

import java.util.Map;

/**
 * A single retrieved chunk with its relevance score and citation info.
 *
 * @param chunkId    the matched chunk
 * @param documentId owning document (for grouping / dedup)
 * @param sourceId   owning source
 * @param title      document title for display
 * @param snippet    highlighted / relevant text excerpt
 * @param uri        locator so the user can open the original
 * @param score      fused relevance score (post-rerank if reranking is on)
 * @param metadata   facets carried through for display/filtering
 */
public record SearchHit(
        String chunkId,
        String documentId,
        String sourceId,
        String title,
        String snippet,
        String uri,
        double score,
        Map<String, Object> metadata) {}
