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

class OpenSearchSearchIndexQueryShapeTest {

    private final OpenSearchSearchIndex index = configured();
    private final float[] vector = {0.1f, 0.2f, 0.3f};

    private static OpenSearchSearchIndex configured() {
        OpenSearchSearchIndex index = new OpenSearchSearchIndex(null, "chunks");
        index.snippetChars = 280;
        index.highlightFragments = 2;
        index.lexicalFields = List.of("text", "title^2");
        index.lexicalType = "best_fields";
        index.minimumShouldMatch = "2<70%";
        index.phraseBoost = 2.0;
        return index;
    }

    private SearchQuery scoped() {
        return new SearchQuery("anything", List.of("kn_1"), Map.of("sourceType", "EMAIL"), 10, Mode.HYBRID, false, null, false);
    }

    private SearchQuery unscoped() {
        return new SearchQuery("anything", List.of(), Map.of(), 10, Mode.HYBRID, false, null, false);
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

    @Test
    void lexicalQueryKeepsFiltersInBoolFilter() {
        JsonNode bool = index.lexicalBody(scoped(), 10).path("query").path("bool");

        assertTrue(bool.path("must").get(0).has("multi_match"), "still a multi_match query");
        JsonNode filters = bool.path("filter");
        assertTrue(filters.isArray() && filters.size() == 2, "filters stay in bool.filter for BM25");
        assertEquals("kn_1", filters.get(0).path("terms").path("knowledgeId").get(0).asText());
    }

    @Test
    void bothLegsExcludeTheEmbeddingFromSource() {
        for (JsonNode body : List.of(index.lexicalBody(scoped(), 10),
                index.vectorBody(scoped(), vector, 10))) {
            JsonNode excludes = body.path("_source").path("excludes");
            assertTrue(excludes.isArray(), "_source.excludes missing from: " + body);
            assertEquals("embedding", excludes.get(0).asText());
        }
    }

    @Test
    void onlyTheLexicalLegAsksForHighlightFragments() {
        JsonNode highlight = index.lexicalBody(scoped(), 10).path("highlight");
        assertTrue(highlight.path("fields").has("text"), "highlight text: " + highlight);
        assertTrue(highlight.path("fields").has("title"));
        assertEquals(2, highlight.path("number_of_fragments").asInt());

        assertTrue(index.vectorBody(scoped(), vector, 10).path("highlight").isMissingNode(),
                "the knn leg must not request highlighting");
    }

    @Test
    void highlightingIsOmittedWhenTurnedOff() {
        OpenSearchSearchIndex off = configured();
        off.highlightFragments = 0;

        assertTrue(off.lexicalBody(scoped(), 10).path("highlight").isMissingNode(),
                "0 fragments means no highlight block at all");
    }

    @Test
    void lexicalQueryRequiresAShareOfTheQueryToMatch() {
        JsonNode multiMatch = index.lexicalBody(scoped(), 10)
                .path("query").path("bool").path("must").get(0).path("multi_match");

        assertEquals("2<70%", multiMatch.path("minimum_should_match").asText());
        assertEquals("best_fields", multiMatch.path("type").asText());
    }

    @Test
    void lexicalQueryBoostsTheTitleField() {
        JsonNode fields = index.lexicalBody(scoped(), 10)
                .path("query").path("bool").path("must").get(0).path("multi_match").path("fields");

        assertEquals("text", fields.get(0).asText());
        assertEquals("title^2", fields.get(1).asText(),
                "an untitled boost let a short unrelated chunk outrank the document named for the query");
    }

    @Test
    void lexicalQueryAddsThePhraseMatchAsAnOptionalBoost() {
        JsonNode bool = index.lexicalBody(scoped(), 10).path("query").path("bool");
        JsonNode phrase = bool.path("should").get(0).path("match_phrase").path("text");

        assertEquals("anything", phrase.path("query").asText());
        assertEquals(2.0, phrase.path("boost").asDouble());
        assertTrue(bool.path("must").get(0).has("multi_match"),
                "the phrase clause must not replace the term query");
    }

    @Test
    void anEmptyFieldListFallsBackToTextAndTitle() {
        OpenSearchSearchIndex bare = configured();
        bare.lexicalFields = List.of();
        bare.minimumShouldMatch = "";
        bare.phraseBoost = 0;

        JsonNode bool = bare.lexicalBody(scoped(), 10).path("query").path("bool");
        JsonNode multiMatch = bool.path("must").get(0).path("multi_match");
        assertEquals(2, multiMatch.path("fields").size());
        assertFalse(multiMatch.has("minimum_should_match"), "blank means send nothing");
        assertTrue(bool.path("should").isMissingNode(), "0 boost omits the phrase clause");
    }
}
