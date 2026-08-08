package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;

/**
 * Outbound REST payload for a search result.
 *
 * @param hits   ranked results, best first
 * @param answer grounded answer citing hits as {@code [n]} (1-based into {@code hits}), or null
 *               when the request did not ask for one
 * @param tookMs wall-clock time the search took
 */
public record SearchResponseDto(List<Hit> hits, String answer, long tookMs) {

    /**
     * One result. Component order mirrors
     * {@link io.personalassistant.domain.model.search.SearchHit}.
     *
     * @param chunkId     the matched chunk, {@code <entityId>_<ordinal>} — lets a caller pin the
     *                    exact passage rather than the whole entity
     * @param entityId    owning entity, for grouping and for the per-entity index endpoints
     * @param knowledgeId owning knowledge — what lets a caller attribute a hit to its source
     * @param title       entity title for display
     * @param snippet     relevant text excerpt
     * @param uri         locator so the user can open the original
     * @param score       fused relevance score
     * @param metadata    facets carried through for display/filtering
     */
    public record Hit(
            String chunkId,
            String entityId,
            String knowledgeId,
            String title,
            String snippet,
            String uri,
            double score,
            Map<String, Object> metadata) {}

    public static SearchResponseDto from(SearchResponse r) {
        var hits = r.hits().stream()
                .map(h -> new Hit(h.chunkId(), h.entityId(), h.knowledgeId(), h.title(), h.snippet(),
                        h.uri(), h.score(), h.metadata()))
                .toList();
        return new SearchResponseDto(hits, r.answer(), r.tookMs());
    }
}
