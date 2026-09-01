package io.personalassistant.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire contract for search results. {@code chunkId} and {@code knowledgeId} were previously
 * dropped in the mapping even though {@link SearchHit} carried them, which left a caller unable to
 * attribute a hit to its source or pin the exact passage — hence the explicit assertions here.
 */
class SearchResponseDtoTest {

    private static SearchHit hit() {
        return new SearchHit("ent_1_0", "ent_1", "kn_9", 3, "Quarterly report",
                "revenue grew 12% year on year, driven by …", "revenue grew 12%",
                "file:///docs/q4.pdf", 0.87, Map.of("author", "ada"));
    }

    @Test
    void carriesEveryHitFieldIncludingChunkAndKnowledgeIds() {
        SearchResponseDto dto = SearchResponseDto.from(
                new SearchResponse(List.of(hit()), null, null, 42L));

        assertEquals(1, dto.hits().size());
        SearchResponseDto.Hit mapped = dto.hits().get(0);
        assertEquals("ent_1_0", mapped.chunkId(), "chunkId identifies the matched passage");
        assertEquals("ent_1", mapped.entityId());
        assertEquals("kn_9", mapped.knowledgeId(), "knowledgeId is what attributes a hit to its source");
        assertEquals(3, mapped.ordinal(), "ordinal orders several hits from the same document");
        assertEquals("Quarterly report", mapped.title());
        assertEquals("revenue grew 12%", mapped.snippet());
        assertEquals("file:///docs/q4.pdf", mapped.uri());
        assertEquals(0.87, mapped.score());
        assertEquals(Map.of("author", "ada"), mapped.metadata());
        assertEquals(42L, dto.tookMs());
        assertNull(dto.answer(), "answer stays null when the request didn't ask for one");
        assertNull(dto.answerError(), "and so does answerError");
    }

    /**
     * The full chunk text exists to ground the answer, not to be displayed. Mapping it onto the wire
     * would multiply the payload for a field nothing renders, so the DTO carries only the snippet.
     */
    @Test
    void doesNotShipTheFullChunkTextOnTheWire() {
        SearchResponseDto.Hit mapped = SearchResponseDto.from(
                new SearchResponse(List.of(hit()), null, null, 1L)).hits().get(0);

        assertEquals("revenue grew 12%", mapped.snippet(), "the display excerpt, not the whole chunk");
        assertTrue(Arrays.stream(SearchResponseDto.Hit.class.getRecordComponents())
                        .noneMatch(c -> c.getName().equals("text")),
                "Hit must not expose the grounding text; adding it multiplies the payload for a field "
                        + "the console does not render");
    }

    @Test
    void passesTheGroundedAnswerThrough() {
        SearchResponse response = new SearchResponse(List.of(), "Revenue grew 12% [1].", null, 7L);

        assertEquals("Revenue grew 12% [1].", SearchResponseDto.from(response).answer());
    }

    /**
     * An unavailable LLM must not cost the caller its hits. The service catches the failure and reports
     * it beside the results; before that, the whole request 500d and the retrieved hits were discarded.
     */
    @Test
    void carriesTheAnswerErrorBesideTheHits() {
        SearchResponseDto dto = SearchResponseDto.from(
                new SearchResponse(List.of(hit()), null, "LLM API 401: invalid api key", 12L));

        assertEquals(1, dto.hits().size(), "hits survive a failed answer");
        assertNull(dto.answer());
        assertEquals("LLM API 401: invalid api key", dto.answerError());
    }
}
