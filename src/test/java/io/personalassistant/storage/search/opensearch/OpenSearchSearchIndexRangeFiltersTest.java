package io.personalassistant.storage.search.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchQuery.Mode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSearchSearchIndexRangeFiltersTest {

    private final OpenSearchSearchIndex index = new OpenSearchSearchIndex(null, "chunks");

    private ArrayNode filtersFor(Map<String, Object> filters) {
        return index.filters(new SearchQuery("anything", List.of(), filters, 10, Mode.HYBRID, false, null, false));
    }

    @Test
    void aBoundsMapBecomesARangeClause() {
        ArrayNode filters = filtersFor(Map.of("metadata.postedAt",
                Map.of("gte", "2026-08-18T00:00:00Z")));

        assertEquals(1, filters.size());
        JsonNode range = filters.get(0).get("range");
        assertTrue(range != null, "expected a range clause but was: " + filters.get(0));
        assertEquals("2026-08-18T00:00:00Z", range.get("metadata.postedAt").get("gte").asText());
    }

    @Test
    void bothBoundsAreCarried() {
        JsonNode bounds = filtersFor(Map.of("metadata.sourceRank",
                new java.util.LinkedHashMap<>(Map.of("gte", 10, "lte", 100))))
                .get(0).get("range").get("metadata.sourceRank");

        assertEquals(10, bounds.get("gte").asInt());
        assertEquals(100, bounds.get("lte").asInt());
    }

    @Test
    void numericBoundsStayNumbersRatherThanStrings() {
        JsonNode gte = filtersFor(Map.of("metadata.sourceRank", Map.of("gte", 100L)))
                .get(0).get("range").get("metadata.sourceRank").get("gte");

        assertTrue(gte.isNumber(), "numeric bound must not be serialised as a string: " + gte);
    }

    @Test
    void booleanTermStaysABooleanRatherThanAString() {
        JsonNode value = filtersFor(Map.of("metadata.remote", true)).get(0).get("term").get("metadata.remote");

        assertTrue(value.isBoolean(), "boolean term must not be serialised as a string: " + value);
    }

    @Test
    void anInstantBoundIsWrittenAsIso8601() {
        JsonNode gte = filtersFor(Map.of("metadata.postedAt",
                Map.of("gte", Instant.parse("2026-08-18T00:00:00Z"))))
                .get(0).get("range").get("metadata.postedAt").get("gte");

        assertEquals("2026-08-18T00:00:00Z", gte.asText());
    }

    @Test
    void scalarFiltersStillEmitTermClauses() {
        ArrayNode filters = filtersFor(Map.of("metadata.company", "Acme"));

        assertTrue(filters.get(0).has("term"));
        assertFalse(filters.get(0).has("range"));
        assertEquals("Acme", filters.get(0).get("term").get("metadata.company").asText());
    }

    @Test
    void aMapWithNoRecognisedBoundDoesNotWidenTheSearch() {
        ArrayNode filters = filtersFor(Map.of("metadata.company", Map.of("script", "evil")));

        assertEquals(1, filters.size());
        assertTrue(filters.get(0).has("term"), "expected a term fallback but was: " + filters.get(0));
        assertFalse(filters.get(0).has("range"));
    }
}
