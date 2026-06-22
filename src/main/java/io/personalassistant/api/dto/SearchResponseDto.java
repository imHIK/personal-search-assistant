package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;

/** Outbound REST payload for a search result. */
public record SearchResponseDto(List<Hit> hits, String answer, long tookMs) {

    public record Hit(
            String documentId,
            String title,
            String snippet,
            String uri,
            double score,
            Map<String, Object> metadata) {}

    public static SearchResponseDto from(SearchResponse r) {
        var hits = r.hits().stream()
                .map(h -> new Hit(h.documentId(), h.title(), h.snippet(),
                        h.uri(), h.score(), h.metadata()))
                .toList();
        return new SearchResponseDto(hits, r.answer(), r.tookMs());
    }
}
