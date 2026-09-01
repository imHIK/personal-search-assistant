package io.personalassistant.retrieval;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.StubSearchAgent;
import io.personalassistant.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Fan-out and fusion across the facets derived from a source document. */
class FacetedRetrievalTest {

    /** Returns a per-facet ranking, and records the query texts it was asked for. */
    private static final class ScriptedRetriever implements Retriever {
        final Map<String, List<SearchHit>> byQuery = new java.util.HashMap<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
            asked.add(query.text());
            return byQuery.getOrDefault(query.text(), List.of());
        }
    }

    private static SearchHit hit(String chunkId) {
        return new SearchHit(chunkId, "ent_" + chunkId, "kn_1", 0, chunkId, "text", "s", "u", 0.0,
                Map.of());
    }

    private final InMemoryEntityRepository entities = new InMemoryEntityRepository();

    private FacetedRetrieval retrieval(ScriptedRetriever retriever, String facetsJson) {
        Entity cv = TestData.ingestedText("ent_cv", "kn_1", "cv.txt", "A backend engineering CV.");
        entities.store.put(cv.id(), cv);
        DocumentQueryPlanner planner =
                new DocumentQueryPlanner(entities, new StubSearchAgent(facetsJson),
                        new RecordingSearchIndex(), 40_000, 8);
        return new FacetedRetrieval(planner, retriever, new FakeEmbeddingProvider(768), 60);
    }

    private static SearchQuery byDocument() {
        return new SearchQuery("", List.of(), Map.of(), 10, SearchQuery.Mode.HYBRID, false, null,
                false, "ent_cv");
    }

    @Test
    void runsOneRetrievalPerFacet() {
        ScriptedRetriever retriever = new ScriptedRetriever();

        retrieval(retriever, "{\"facets\": [\"backend engineer\", \"platform engineer\"]}")
                .retrieve(byDocument(), 10);

        Assertions.assertEquals(List.of("backend engineer", "platform engineer"), retriever.asked);
    }

    @Test
    void aHitFoundBySeveralFacetsOutranksOneFoundVeryWellByASingleFacet() {
        // The reason for decomposing at all: breadth of match beats depth in one direction.
        ScriptedRetriever retriever = new ScriptedRetriever();
        retriever.byQuery.put("backend engineer", List.of(hit("narrow"), hit("broad")));
        retriever.byQuery.put("platform engineer", List.of(hit("broad")));
        retriever.byQuery.put("distributed systems", List.of(hit("broad")));

        List<SearchHit> fused = retrieval(retriever,
                "{\"facets\": [\"backend engineer\", \"platform engineer\", \"distributed systems\"]}")
                .retrieve(byDocument(), 10);

        Assertions.assertEquals("broad", fused.get(0).chunkId());
        Assertions.assertEquals(2, fused.size(), "each hit appears once, however many facets found it");
    }

    @Test
    void aFacetThatMatchesNothingDoesNotBreakTheFusion() {
        ScriptedRetriever retriever = new ScriptedRetriever();
        retriever.byQuery.put("backend engineer", List.of(hit("a")));

        List<SearchHit> fused = retrieval(retriever,
                "{\"facets\": [\"backend engineer\", \"underwater basket weaving\"]}")
                .retrieve(byDocument(), 10);

        Assertions.assertEquals(List.of("a"), fused.stream().map(SearchHit::chunkId).toList());
    }

    @Test
    void theLimitIsRespectedAfterFusion() {
        ScriptedRetriever retriever = new ScriptedRetriever();
        retriever.byQuery.put("backend engineer", List.of(hit("a"), hit("b"), hit("c")));

        Assertions.assertEquals(2,
                retrieval(retriever, "{\"facets\": [\"backend engineer\"]}").retrieve(byDocument(), 2).size());
    }
}
