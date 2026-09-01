package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.retrieval.DocumentQueryPlanner;
import io.personalassistant.retrieval.DuplicateCollapser;
import io.personalassistant.retrieval.FacetedRetrieval;
import io.personalassistant.retrieval.NoopReranker;
import io.personalassistant.retrieval.Retriever;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.StubSearchAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Read-path orchestration. Two behaviours are load-bearing enough to pin down: an answer failure must
 * not cost the caller its hits, and {@code topK} must be bounded before it is multiplied into the
 * OpenSearch {@code size} and knn {@code k}.
 */
class DefaultSearchServiceTest {

    /** Records the limit it was asked for so the candidate over-fetch can be asserted. */
    private static final class RecordingRetriever implements Retriever {
        int lastLimit;
        List<SearchHit> result = List.of();

        @Override
        public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
            this.lastLimit = limit;
            return result;
        }
    }

    private static SearchHit hit(String chunkId) {
        return hit(chunkId, "full text");
    }

    private static SearchHit hit(String chunkId, String text) {
        return new SearchHit(chunkId, "ent_" + chunkId, "kn_1", 0, "Holidays 2026", text, "snippet",
                "file:///holidays.xlsx", 1.0, Map.of());
    }

    /** A collapser with the shipped defaults; unused unless a query opts in. */
    private static DuplicateCollapser collapser() {
        return new DuplicateCollapser(5, 0.85, 100);
    }

    /** Faceted retrieval is only reached by a document query; these tests all pass typed text. */
    private static FacetedRetrieval faceted(RecordingRetriever retriever, SearchAgent agent) {
        return new FacetedRetrieval(
                new DocumentQueryPlanner(new InMemoryEntityRepository(), agent,
                        new RecordingSearchIndex(), 40_000, 8),
                retriever, new FakeEmbeddingProvider(768), 60);
    }

    private DefaultSearchService service(RecordingRetriever retriever, SearchAgent agent) {
        DefaultSearchService svc = new DefaultSearchService(
                new FakeEmbeddingProvider(768), retriever, faceted(retriever, agent),
                new NoopReranker(), collapser(), agent);
        svc.maxTopK = 100;
        svc.candidateMultiplier = 4;
        return svc;
    }

    private static SearchQuery ask(int topK, boolean answer) {
        return new SearchQuery("holidays", List.of(), Map.of(), topK, SearchQuery.Mode.HYBRID, answer, null, false, null);
    }

    /**
     * The failure this exists for: {@code agent.answer} was called uncaught, so an unavailable LLM
     * turned a successful retrieval into a 500 and the hits were discarded. The console hid it by
     * retrying without the answer flag; every other API consumer just lost its results.
     */
    @Test
    void anAnswerFailureKeepsTheHitsAndReportsTheError() {
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(hit("ent_1_0"), hit("ent_1_1"));
        SearchAgent failing = new StubSearchAgent((query, hits) -> {
            throw new IllegalStateException("LLM API 401: invalid api key");
        });

        SearchResponse response = service(retriever, failing).search(ask(10, true));

        assertEquals(2, response.hits().size(), "retrieval succeeded, so the hits must survive");
        assertNull(response.answer());
        assertEquals("LLM API 401: invalid api key", response.answerError());
    }

    @Test
    void doesNotTouchTheAgentWhenNoAnswerWasAskedFor() {
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(hit("ent_1_0"));
        SearchAgent exploding = new StubSearchAgent((query, hits) -> {
            throw new AssertionError("the agent must not be called when answer=false");
        });

        SearchResponse response = service(retriever, exploding).search(ask(10, false));

        assertNull(response.answer());
        assertNull(response.answerError());
    }

    @Test
    void passesTheAnswerThroughOnSuccess() {
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(hit("ent_1_0"));

        SearchResponse response = service(retriever, new StubSearchAgent("Republic Day … [1]")).search(ask(10, true));

        assertEquals("Republic Day … [1]", response.answer());
        assertNull(response.answerError());
    }

    @Test
    void overFetchesCandidatesForFusionToWorkWith() {
        RecordingRetriever retriever = new RecordingRetriever();

        service(retriever, new StubSearchAgent("")).search(ask(10, false));

        assertEquals(40, retriever.lastLimit, "topK * candidate-multiplier");
    }

    /**
     * topK reaches OpenSearch multiplied, as both {@code size} and knn {@code k}, so an unbounded value
     * is a request for an unbounded result set. A non-positive one previously produced a negative
     * {@code size} with no validation anywhere on the path.
     */
    @Test
    void clampsTopKIntoTheConfiguredRange() {
        RecordingRetriever high = new RecordingRetriever();
        service(high, new StubSearchAgent("")).search(ask(5_000, false));
        assertEquals(400, high.lastLimit, "clamped to max-top-k (100) before over-fetching");

        RecordingRetriever low = new RecordingRetriever();
        service(low, new StubSearchAgent("")).search(ask(0, false));
        assertEquals(4, low.lastLimit, "0 is raised to 1, never a negative size");
    }

    @Test
    void skipsTheQueryEmbeddingForAPurelyLexicalSearch() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        RecordingRetriever retriever = new RecordingRetriever();
        StubSearchAgent agent = new StubSearchAgent("");
        DefaultSearchService svc = new DefaultSearchService(
                embeddings, retriever, faceted(retriever, agent), new NoopReranker(), collapser(), agent);
        svc.maxTopK = 100;
        svc.candidateMultiplier = 4;

        svc.search(new SearchQuery("holidays", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL, false, null, false, null));

        assertEquals(0, embeddings.embedCalls, "a lexical search must not pay for an embedding");

        svc.search(ask(10, false));
        assertTrue(embeddings.embedCalls > 0, "hybrid and semantic do need one");
    }

    @Test
    void duplicatesAreLeftAloneUnlessTheCallerAsksForCollapsing() {
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(hit("a", "the same body text repeated"), hit("b", "the same body text repeated"));

        SearchResponse response = service(retriever, new StubSearchAgent("")).search(ask(10, false));

        assertEquals(2, response.hits().size(), "collapsing must be opt-in");
    }

    @Test
    void collapsingRunsBeforeTheTopKTrimSoTheResultSetStaysFull() {
        // Collapsing after the trim would return fewer than topK: the duplicates would already have
        // eaten slots that a distinct candidate further down could have filled.
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(
                hit("a", "alpha body about one subject"),
                hit("b", "alpha body about one subject"),
                hit("c", "beta body about another subject"));
        SearchQuery collapsing = new SearchQuery("holidays", List.of(), Map.of(), 2,
                SearchQuery.Mode.HYBRID, false, null, true, null);

        SearchResponse response = service(retriever, new StubSearchAgent("")).search(collapsing);

        assertEquals(List.of("a", "c"),
                response.hits().stream().map(SearchHit::chunkId).toList());
    }
}
