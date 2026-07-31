package io.personalassistant.storage.search.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchQuery.Mode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenSearchSearchIndex#filters(SearchQuery)} focused on field resolution.
 * The RestClient is unused by {@code filters()}, so {@code null} is passed deliberately.
 */
class OpenSearchSearchIndexFiltersTest {

    private final OpenSearchSearchIndex index = new OpenSearchSearchIndex(null, "chunks");

    private SearchQuery queryWith(List<String> knowledgeIds, Map<String, Object> filters) {
        return new SearchQuery("anything", knowledgeIds, filters, 10, Mode.HYBRID, false);
    }

    /** Pulls the single {field: value} pair out of a {"term": {...}} clause. */
    private Map.Entry<String, JsonNode> termOf(JsonNode clause) {
        assertTrue(clause.has("term"), "expected a term clause but was: " + clause);
        JsonNode term = clause.get("term");
        return term.fields().next();
    }

    @Test
    void usesTopLevelKeywordFieldVerbatim() {
        ArrayNode filters = index.filters(queryWith(List.of(), Map.of("sourceType", "EMAIL")));

        assertEquals(1, filters.size());
        var entry = termOf(filters.get(0));
        assertEquals("sourceType", entry.getKey(), "top-level field must not be prefixed");
        assertEquals("EMAIL", entry.getValue().asText());
    }

    @Test
    void usesNestedMetadataFieldVerbatim() {
        ArrayNode filters = index.filters(queryWith(List.of(), Map.of("metadata.author", "jane")));

        assertEquals(1, filters.size());
        var entry = termOf(filters.get(0));
        assertEquals("metadata.author", entry.getKey(), "caller-supplied path must be kept as-is");
        assertEquals("jane", entry.getValue().asText());
    }

    @Test
    void supportsMixedTopLevelAndMetadataFilters() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("uri", "doc://1");
        raw.put("metadata.label", "urgent");
        ArrayNode filters = index.filters(queryWith(List.of(), raw));

        assertEquals(2, filters.size());
        Map<String, String> byField = new LinkedHashMap<>();
        filters.forEach(clause -> {
            var entry = termOf(clause);
            byField.put(entry.getKey(), entry.getValue().asText());
        });
        assertEquals("doc://1", byField.get("uri"));
        assertEquals("urgent", byField.get("metadata.label"));
    }

    @Test
    void coercesNonStringValuesToString() {
        ArrayNode filters = index.filters(queryWith(List.of(), Map.of("ordinal", 3)));

        var entry = termOf(filters.get(0));
        assertEquals("ordinal", entry.getKey());
        assertEquals("3", entry.getValue().asText());
    }

    @Test
    void knowledgeIdsProduceTermsClauseAlongsideFilters() {
        ArrayNode filters = index.filters(
                queryWith(List.of("k1", "k2"), Map.of("sourceType", "EMAIL")));

        assertEquals(2, filters.size());
        // First clause is the knowledgeId terms filter.
        assertTrue(filters.get(0).has("terms"), "expected knowledgeId terms clause first");
        JsonNode ids = filters.get(0).get("terms").get("knowledgeId");
        assertEquals(2, ids.size());
        assertEquals("k1", ids.get(0).asText());
        assertEquals("k2", ids.get(1).asText());
    }

    @Test
    void emptyFiltersAndKnowledgeIdsProduceNoClauses() {
        ArrayNode filters = index.filters(queryWith(List.of(), Map.of()));
        assertEquals(0, filters.size());
    }
}
