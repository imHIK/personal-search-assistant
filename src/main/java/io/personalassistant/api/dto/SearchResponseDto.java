package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;

/**
 * Outbound REST payload for a search result.
 *
 * @param hits        ranked results, best first
 * @param answer      grounded answer citing hits as {@code [n]} (1-based into {@code hits}), or null
 *                    when the request did not ask for one
 * @param answerError why no answer came back despite being asked for — an unavailable or
 *                    misconfigured LLM. The hits are still valid and populated; a client can show
 *                    results and surface this as a notice rather than treating the search as failed
 * @param tookMs      wall-clock time the search took
 */
public record SearchResponseDto(List<Hit> hits, String answer, String answerError, long tookMs) {

    /**
     * One result. Component order mirrors
     * {@link io.personalassistant.domain.model.search.SearchHit}.
     *
     * @param chunkId     the matched chunk, {@code <entityId>_<ordinal>} — lets a caller pin the
     *                    exact passage rather than the whole entity
     * @param entityId    owning entity, for grouping and for the per-entity index endpoints
     * @param knowledgeId owning knowledge — what lets a caller attribute a hit to its source
     * @param ordinal     position of the chunk within its entity, so several hits from one document can
     *                    be shown in document order rather than score order
     * @param title       entity title for display
     * @param snippet     relevant text excerpt — the matching region where highlighting found one
     * @param uri         locator so the user can open the original
     * @param score       fused relevance score
     * @param metadata    facets carried through for display/filtering
     */
    public record Hit(
            String chunkId,
            String entityId,
            String knowledgeId,
            int ordinal,
            String title,
            String snippet,
            String uri,
            double score,
            Map<String, Object> metadata) {}

    /**
     * The full chunk {@code text} on a {@link io.personalassistant.domain.model.search.SearchHit} is
     * deliberately <em>not</em> mapped onto the wire. It exists so the answer is grounded in whole
     * chunks; the console renders the snippet, and shipping both would multiply the payload for data
     * nothing displays.
     */
    public static SearchResponseDto from(SearchResponse r) {
        var hits = r.hits().stream()
                .map(h -> new Hit(h.chunkId(), h.entityId(), h.knowledgeId(), h.ordinal(), h.title(),
                        h.snippet(), h.uri(), h.score(), h.metadata()))
                .toList();
        return new SearchResponseDto(hits, r.answer(), r.answerError(), r.tookMs());
    }
}
