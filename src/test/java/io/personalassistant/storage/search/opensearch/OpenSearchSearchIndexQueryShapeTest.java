package io.personalassistant.storage.search.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchQuery.Mode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where the scoping clauses sit in the request body — the difference between a correct and a
 * silently-wrong semantic search.
 *
 * <p>B4 regression. A filter in the surrounding {@code bool.filter} is applied <em>after</em>
 * OpenSearch has picked the global k nearest neighbours, so scoping a search to one knowledge in a
 * large corpus drops most or all of the top-k and returns far fewer hits than asked for — sometimes
 * none, while plenty of relevant chunks exist. Nested inside the knn clause, the filter is honoured
 * during graph traversal and k counts matching documents.
 *
 * <p>The RestClient is unused by the body builders, so {@code null} is passed deliberately (same
 * convention as {@link OpenSearchSearchIndexFiltersTest}).
 */
class OpenSearchSearchIndexQueryShapeTest {

    private final OpenSearchSearchIndex index = new OpenSearchSearchIndex(null, "chunks");
    private final float[] vector = {0.1f, 0.2f, 0.3f};

    private SearchQuery scoped() {
        return new SearchQuery("anything", List.of("kn_1"), Map.of("sourceType", "EMAIL"), 10, Mode.HYBRID, false);
    }

    private SearchQuery unscoped() {
        return new SearchQuery("anything", List.of(), Map.of(), 10, Mode.HYBRID, false);
    }

    @Test
    void vectorQueryNestsFiltersInsideTheKnnClause() {
        JsonNode body = index.vectorBody(scoped(), vector, 10);
        JsonNode bool = body.path("query").path("bool");
        JsonNode embedding = bool.path("must").get(0).path("knn").path("embedding");

        JsonNode nested = embedding.path("filter").path("bool").path("filter");
        assertTrue(nested.isArray(), "filters must live inside knn.embedding.filter, was: " + body);
        assertEquals(2, nested.size(), "both the knowledge scope and the metadata term");
        assertEquals("kn_1", nested.get(0).path("terms").path("knowledgeId").get(0).asText());

        assertTrue(bool.path("filter").isMissingNode(),
                "the outer bool.filter must be empty, or the filter is applied twice — once too late");
    }

    @Test
    void vectorQueryWithoutFiltersOmitsTheKnnFilterKey() {
        JsonNode embedding = index.vectorBody(unscoped(), vector, 10)
                .path("query").path("bool").path("must").get(0).path("knn").path("embedding");

        assertFalse(embedding.has("filter"), "an unscoped search must not send an empty filter");
        assertEquals(3, embedding.path("vector").size());
    }

    @Test
    void knnKMatchesTheRequestedLimit() {
        assertEquals(25, index.vectorBody(scoped(), vector, 25)
                .path("query").path("bool").path("must").get(0)
                .path("knn").path("embedding").path("k").asInt());
        assertEquals(25, index.vectorBody(scoped(), vector, 25).path("size").asInt());
    }

    /** The BM25 path is deliberately untouched: bool.filter is already applied during scoring there. */
    @Test
    void lexicalQueryKeepsFiltersInBoolFilter() {
        JsonNode bool = index.lexicalBody(scoped(), 10).path("query").path("bool");

        assertTrue(bool.path("must").get(0).has("multi_match"), "still a multi_match query");
        JsonNode filters = bool.path("filter");
        assertTrue(filters.isArray() && filters.size() == 2, "filters stay in bool.filter for BM25");
        assertEquals("kn_1", filters.get(0).path("terms").path("knowledgeId").get(0).asText());
    }
}
