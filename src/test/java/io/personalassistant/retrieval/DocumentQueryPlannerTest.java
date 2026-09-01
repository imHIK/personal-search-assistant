package io.personalassistant.retrieval;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.StubSearchAgent;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Facet derivation, its cache key, and the degradations that must not fail a search. */
class DocumentQueryPlannerTest {

    private final InMemoryEntityRepository entities = new InMemoryEntityRepository();
    private final RecordingSearchIndex searchIndex = new RecordingSearchIndex();

    private Entity resume(String text) {
        Entity stored = TestData.ingestedText("ent_cv", "kn_1", "cv.txt", text);
        entities.store.put(stored.id(), stored);
        return stored;
    }

    private static SearchQuery byDocument() {
        return new SearchQuery("roles I could do next", List.of(), java.util.Map.of(), 10,
                SearchQuery.Mode.HYBRID, false, null, false, "ent_cv");
    }

    private DocumentQueryPlanner planner(StubSearchAgent agent) {
        return new DocumentQueryPlanner(entities, agent, searchIndex, 40_000, 8);
    }

    @Test
    void derivesFacetsFromTheModelsReply() {
        resume("Ten years of backend engineering in Go and Kubernetes.");
        StubSearchAgent agent = new StubSearchAgent(
                "{\"facets\": [\"senior backend engineer go\", \"platform engineer kubernetes\"]}");

        List<String> facets = planner(agent).facets(byDocument());

        Assertions.assertEquals(
                List.of("senior backend engineer go", "platform engineer kubernetes"), facets);
        Assertions.assertEquals(List.of(DocumentQueryPlanner.TASK_ID), agent.taskIds);
    }

    @Test
    void capsTheFacetCountSoOneReplyCannotDecideTheCostOfASearch() {
        resume("A CV.");
        StubSearchAgent agent = new StubSearchAgent(
                "{\"facets\": [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\"]}");

        Assertions.assertEquals(2, new DocumentQueryPlanner(entities, agent, searchIndex, 40_000, 2)
                .facets(byDocument()).size());
    }

    @Test
    void dropsBlankAndDuplicateFacets() {
        resume("A CV.");
        StubSearchAgent agent = new StubSearchAgent(
                "{\"facets\": [\"backend engineer\", \"  \", \"backend engineer\", \"data engineer\"]}");

        Assertions.assertEquals(List.of("backend engineer", "data engineer"),
                planner(agent).facets(byDocument()));
    }

    @Test
    void cachesByChecksumSoTheModelIsAskedOnce() {
        resume("A CV.");
        StubSearchAgent agent = new StubSearchAgent("{\"facets\": [\"backend engineer\"]}");
        DocumentQueryPlanner planner = planner(agent);

        planner.facets(byDocument());
        planner.facets(byDocument());

        Assertions.assertEquals(1, agent.taskIds.size(), "the second call must come from the cache");
    }

    @Test
    void anUnavailableModelDegradesToTheDocumentsOpeningTextRatherThanFailing() {
        // A degraded model must not turn into a broken feature.
        resume("Ten years of backend engineering in Go and Kubernetes.");
        StubSearchAgent agent = StubSearchAgent.throwing(new IllegalStateException("LLM API 401"));

        List<String> facets = planner(agent).facets(byDocument());

        Assertions.assertEquals(1, facets.size());
        Assertions.assertTrue(facets.get(0).startsWith("Ten years of backend engineering"));
    }

    @Test
    void anUnreadableReplyAlsoFallsBackAndIsNotCached() {
        resume("Ten years of backend engineering.");
        StubSearchAgent agent = new StubSearchAgent("I'm afraid I can't do that.");
        DocumentQueryPlanner planner = planner(agent);

        Assertions.assertEquals(1, planner.facets(byDocument()).size());
        planner.facets(byDocument());
        Assertions.assertEquals(2, agent.taskIds.size(),
                "a fallback must not be cached; the model may be available next time");
    }

    @Test
    void rejectsADocumentFarPastTheLimitRatherThanTruncatingIt() {
        resume("x".repeat(5_000));
        StubSearchAgent agent = new StubSearchAgent("{\"facets\": [\"a\"]}");

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DocumentQueryPlanner(entities, agent, searchIndex, 1_000, 8).facets(byDocument()));
    }

    @Test
    void readsAFileBackedEntityFromItsIndexedChunks() {
        // LOCAL_FS — the obvious place to keep a CV — always stores a fileRef and never inline text,
        // so without this fallback the feature cannot read the most ordinary source there is.
        Entity file = TestData.ingestedFile("ent_cv", "kn_1", "cv.pdf", "/tmp/cv.pdf", "application/pdf");
        entities.store.put(file.id(), file);
        searchIndex.chunkTexts.put("ent_cv", List.of("Ten years of backend engineering.", "Go and Kubernetes."));
        StubSearchAgent agent = new StubSearchAgent("{\"facets\": [\"backend engineer go\"]}");

        Assertions.assertEquals(List.of("backend engineer go"), planner(agent).facets(byDocument()));
    }

    @Test
    void rejectsAnEntityWithNeitherInlineTextNorChunks() {
        Entity file = TestData.ingestedFile("ent_cv", "kn_1", "cv.pdf", "/tmp/cv.pdf", "application/pdf");
        entities.store.put(file.id(), file);

        String message = Assertions.assertThrows(IllegalArgumentException.class,
                () -> planner(new StubSearchAgent("{}")).facets(byDocument())).getMessage();

        Assertions.assertTrue(message.contains("index it first"), message);
    }

    @Test
    void reportsAnUnknownEntityAsMissing() {
        Assertions.assertThrows(NoSuchElementException.class,
                () -> planner(new StubSearchAgent("{}")).facets(byDocument()));
    }
}
