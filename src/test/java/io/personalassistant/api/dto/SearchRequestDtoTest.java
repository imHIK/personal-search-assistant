package io.personalassistant.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the wire contract accepts, and what it rejects rather than passing downstream. Both rejections
 * used to be silent failures: a blank query reached OpenSearch as an empty {@code multi_match} — after
 * paying for a query embedding — and an unknown mode surfaced as a raw 500 out of {@code Enum.valueOf}
 * because nothing mapped it. {@code SearchResource} turns these into 400s.
 */
class SearchRequestDtoTest {

    private static SearchRequestDto request(String query, String mode, Integer topK) {
        return new SearchRequestDto(query, null, null, topK, mode, null, null, null, null);
    }

    @Test
    void appliesDefaultsForEverythingOptional() {
        SearchQuery domain = request("holidays", null, null).toDomain();

        assertEquals("holidays", domain.text());
        assertEquals(List.of(), domain.knowledgeIds(), "no scope means all knowledges");
        assertEquals(Map.of(), domain.filters());
        assertEquals(10, domain.topK());
        assertEquals(SearchQuery.Mode.HYBRID, domain.mode(), "hybrid is the default retrieval strategy");
        assertFalse(domain.answer(), "answering is opt-in — it costs an LLM call");
    }

    @Test
    void rejectsABlankQuery() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> request("   ", null, null).toDomain())
                .getMessage().contains("blank"));
        assertThrows(IllegalArgumentException.class, () -> request(null, null, null).toDomain());
    }

    @Test
    void rejectsAnUnknownModeWithTheValidOnesNamed() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> request("holidays", "FUZZY", null).toDomain()).getMessage();

        assertTrue(message.contains("FUZZY"), "say what was rejected: " + message);
        assertTrue(message.contains("HYBRID") && message.contains("LEXICAL") && message.contains("SEMANTIC"),
                "and what would have been accepted: " + message);
    }

    @Test
    void acceptsEveryModeItAdvertises() {
        for (SearchQuery.Mode mode : SearchQuery.Mode.values()) {
            assertEquals(mode, request("holidays", mode.name(), null).toDomain().mode());
        }
        assertEquals(SearchQuery.Mode.HYBRID, request("holidays", "  ", null).toDomain().mode(),
                "a blank mode falls back to the default rather than failing");
    }

    /** Out-of-range values are policy, clamped by the service — the DTO only carries them through. */
    @Test
    void carriesTopKThroughUnclampedForTheServiceToBound() {
        assertEquals(5_000, request("holidays", null, 5_000).toDomain().topK());
        assertEquals(0, request("holidays", null, 0).toDomain().topK());
    }
}
