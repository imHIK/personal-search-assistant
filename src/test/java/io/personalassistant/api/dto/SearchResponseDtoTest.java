package io.personalassistant.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire contract for search results. {@code chunkId} and {@code knowledgeId} were previously
 * dropped in the mapping even though {@link SearchHit} carried them, which left a caller unable to
 * attribute a hit to its source or pin the exact passage — hence the explicit assertions here.
 */
class SearchResponseDtoTest {

    @Test
    void carriesEveryHitFieldIncludingChunkAndKnowledgeIds() {
        SearchHit hit = new SearchHit("ent_1_0", "ent_1", "kn_9", "Quarterly report",
                "revenue grew 12%", "file:///docs/q4.pdf", 0.87, Map.of("author", "ada"));

        SearchResponseDto dto = SearchResponseDto.from(
                new SearchResponse(List.of(hit), null, 42L));

        assertEquals(1, dto.hits().size());
        SearchResponseDto.Hit mapped = dto.hits().get(0);
        assertEquals("ent_1_0", mapped.chunkId(), "chunkId identifies the matched passage");
        assertEquals("ent_1", mapped.entityId());
        assertEquals("kn_9", mapped.knowledgeId(), "knowledgeId is what attributes a hit to its source");
        assertEquals("Quarterly report", mapped.title());
        assertEquals("revenue grew 12%", mapped.snippet());
        assertEquals("file:///docs/q4.pdf", mapped.uri());
        assertEquals(0.87, mapped.score());
        assertEquals(Map.of("author", "ada"), mapped.metadata());
        assertEquals(42L, dto.tookMs());
        assertNull(dto.answer(), "answer stays null when the request didn't ask for one");
    }

    @Test
    void passesTheGroundedAnswerThrough() {
        SearchResponse response = new SearchResponse(List.of(), "Revenue grew 12% [1].", 7L);

        assertEquals("Revenue grew 12% [1].", SearchResponseDto.from(response).answer());
    }
}
