package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchQuery;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record SearchRequestDto(
        String query,
        List<String> knowledgeIds,
        Map<String, Object> filters,
        Integer topK,
        String mode,        // LEXICAL | SEMANTIC | HYBRID
        Boolean answer,
        Integer maxChunksPerEntity,
        Boolean collapseDuplicates) {

    /** Mirrored by {@link SearchQuery#of}. */
    private static final int DEFAULT_TOP_K = 10;

    /** @throws IllegalArgumentException on a blank query or an unknown mode */
    public SearchQuery toDomain() {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return new SearchQuery(
                query,
                knowledgeIds == null ? List.of() : knowledgeIds,
                filters == null ? Map.of() : filters,
                topK == null ? DEFAULT_TOP_K : topK,
                parseMode(mode),
                answer != null && answer,
                maxChunksPerEntity,
                collapseDuplicates != null && collapseDuplicates);
    }

    private static SearchQuery.Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchQuery.Mode.HYBRID;
        }
        try {
            return SearchQuery.Mode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown search mode \"" + mode + "\"; expected one of "
                    + Arrays.stream(SearchQuery.Mode.values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", ")));
        }
    }
}
