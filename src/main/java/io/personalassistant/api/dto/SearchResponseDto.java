package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;

/**
 * @param answer cites hits as {@code [n]}, 1-based into {@code hits}; null unless asked for
 * @param answerError why no answer came back although one was asked for; the hits are still valid
 * @param vectorError why the query could not be embedded; the search ran keyword-only and the hits are valid
 */
public record SearchResponseDto(List<Hit> hits, String answer, String answerError, String vectorError,
                                long tookMs) {

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

    public record Match(String chunkId, int ordinal, String snippet, double score) {}

    public record Ranking(Integer lexicalRank, Integer vectorRank, double retrievalScore,
                          double groupedScore, double recencyFactor) {}

    /** Chunk text stays off the wire: it exists to ground the answer, and the console renders snippets. */
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
