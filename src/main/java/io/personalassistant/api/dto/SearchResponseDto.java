package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;

/**
 * Outbound REST payload for a search result.
 *
 * @param hits        ranked results, one per entity, best first
 * @param answer      grounded answer citing hits as {@code [n]} (1-based into {@code hits}), or null
 *                    when the request did not ask for one
 * @param answerError why no answer came back despite being asked for — an unavailable or
 *                    misconfigured LLM. The hits are still valid and populated; a client can show
 *                    results and surface this as a notice rather than treating the search as failed
 * @param vectorError why the query could not be embedded — a spent embedding quota, an unreachable
 *                    endpoint. The search then ran keyword-only: the hits are valid, just without the
 *                    semantic leg, so this too is a notice rather than a failure
 * @param tookMs      wall-clock time the search took
 */
public record SearchResponseDto(List<Hit> hits, String answer, String answerError, String vectorError,
                                long tookMs) {

    /**
     * One result: an entity, shown through its best-matching chunk. Component order mirrors
     * {@link io.personalassistant.domain.model.search.SearchHit}.
     *
     * @param chunkId     the best-matching chunk, {@code <entityId>_<ordinal>} — lets a caller pin the
     *                    exact passage rather than the whole entity
     * @param entityId    owning entity, for the per-entity index endpoints
     * @param knowledgeId owning knowledge — what lets a caller attribute a hit to its source
     * @param ordinal     position of the chunk within its entity
     * @param title       entity title for display
     * @param snippet     relevant text excerpt — the matching region where highlighting found one
     * @param uri         locator so the user can open the original
     * @param score       relevance score
     * @param metadata    facets carried through for display/filtering
     * @param moreMatches the entity's other matching chunks, best first — at most
     *                    {@code maxChunksPerEntity - 1} of them
     * @param ranking     how the result reached {@code score}; diagnostic, for tuning
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
            Map<String, Object> metadata,
            List<Match> moreMatches,
            Ranking ranking) {}

    /**
     * A further matching chunk of a result's entity.
     *
     * @param chunkId the matching chunk
     * @param ordinal its position within the entity
     * @param snippet display excerpt
     * @param score   the chunk's own retrieval score
     */
    public record Match(String chunkId, int ordinal, String snippet, double score) {}

    /**
     * Mirror of {@link io.personalassistant.domain.model.search.SearchHit.Ranking}.
     *
     * @param lexicalRank    where the word-match leg ranked the best chunk, or null
     * @param vectorRank     where the meaning leg ranked it, or null
     * @param retrievalScore score out of retrieval
     * @param groupedScore   after the further-matches bonus
     * @param recencyFactor  freshness multiplier; 1 when none applied
     */
    public record Ranking(Integer lexicalRank, Integer vectorRank, double retrievalScore,
                          double groupedScore, double recencyFactor) {}

    /**
     * The full chunk {@code text} on a {@link io.personalassistant.domain.model.search.SearchHit} — and on
     * each of its matches — is deliberately <em>not</em> mapped onto the wire. It exists so the answer is
     * grounded in whole chunks; the console renders snippets, and shipping both would multiply the payload
     * for data nothing displays.
     */
    public static SearchResponseDto from(SearchResponse r) {
        var hits = r.hits().stream()
                .map(h -> new Hit(h.chunkId(), h.entityId(), h.knowledgeId(), h.ordinal(), h.title(),
                        h.snippet(), h.uri(), h.score(), h.metadata(),
                        h.moreMatches().stream()
                                .map(m -> new Match(m.chunkId(), m.ordinal(), m.snippet(), m.score()))
                                .toList(),
                        new Ranking(h.ranking().lexicalRank(), h.ranking().vectorRank(),
                                h.ranking().retrievalScore(), h.ranking().groupedScore(),
                                h.ranking().recencyFactor())))
                .toList();
        return new SearchResponseDto(hits, r.answer(), r.answerError(), r.vectorError(), r.tookMs());
    }
}
