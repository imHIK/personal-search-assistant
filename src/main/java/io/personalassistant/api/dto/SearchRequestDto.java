package io.personalassistant.api.dto;

import io.personalassistant.domain.model.search.SearchQuery;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        Boolean answer,
        Integer maxChunksPerEntity,
        Boolean collapseDuplicates,
        String sourceEntityId) {

    /** Default result count when the caller doesn't ask for one. Mirrored by {@link SearchQuery#of}. */
    private static final int DEFAULT_TOP_K = 10;

    /**
     * Parse the wire payload into a domain query, rejecting what cannot be defaulted. {@code topK} is
     * only carried through here — the service clamps it, the same way it clamps the entity-listing
     * limit — because the ceiling is a config-driven policy, not part of the wire contract.
     *
     * @throws IllegalArgumentException on a blank query or an unknown mode; the resource maps this to a
     *                                 400. Previously a blank query reached OpenSearch as an empty
     *                                 {@code multi_match} (matching nothing, after paying for a query
     *                                 embedding) and an unknown mode surfaced as a raw 500 from
     *                                 {@code Enum.valueOf}.
     */
    public SearchQuery toDomain() {
        boolean hasSourceDocument = sourceEntityId != null && !sourceEntityId.isBlank();
        if ((query == null || query.isBlank()) && !hasSourceDocument) {
            // A document query supplies its own text, so a blank query is only a problem without one.
            throw new IllegalArgumentException("query must not be blank unless sourceEntityId is set");
        }
        return new SearchQuery(
                query == null ? "" : query,
                knowledgeIds == null ? List.of() : knowledgeIds,
                filters == null ? Map.of() : filters,
                topK == null ? DEFAULT_TOP_K : topK,
                parseMode(mode),
                answer != null && answer,
                maxChunksPerEntity,
                collapseDuplicates != null && collapseDuplicates,
                sourceEntityId);
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
