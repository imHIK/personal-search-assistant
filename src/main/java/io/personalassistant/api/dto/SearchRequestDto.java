package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;
import java.util.Map;

/**
 * Inbound REST payload for a search. Kept separate from the domain {@link SearchQuery} so the
 * wire contract can evolve independently of the core model. Defaults are applied here.
 */
public record SearchRequestDto(
        String query,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        Integer topK,
        String mode,        // LEXICAL | SEMANTIC | HYBRID
        Boolean answer) {

    public SearchQuery toDomain() {
        return new SearchQuery(
                query,
                knowledgeIds == null ? List.of() : knowledgeIds,
                filters == null ? Map.of() : filters,
                topK == null ? 10 : topK,
                mode == null ? SearchQuery.Mode.HYBRID : SearchQuery.Mode.valueOf(mode),
                answer != null && answer);
    }
}
