package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.retrieval.DuplicateCollapser;
import io.personalassistant.retrieval.EntityGrouper;
import io.personalassistant.retrieval.NoopReranker;
import io.personalassistant.retrieval.QueryEmbedder;
import io.personalassistant.retrieval.RecencyBoost;
import io.personalassistant.retrieval.Retriever;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import io.personalassistant.testsupport.StubSearchAgent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Read-path orchestration. Three behaviours are load-bearing enough to pin down: an answer failure must
 * not cost the caller its hits, the candidate pool must be bounded before it becomes the OpenSearch
 * {@code size} and knn {@code k}, and topK must count entities rather than chunks.
 */
class DefaultSearchServiceTest {

    /** Records the limit it was asked for so the candidate over-fetch can be asserted. */
    private static final class RecordingRetriever implements Retriever {
        int lastLimit;
        SearchQuery.Mode lastMode;
        List<SearchHit> result = List.of();

        @Override
        public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
            this.lastLimit = limit;
            this.lastMode = query.mode();
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
        return new DuplicateCollapser(5, 0.85, 100, 0.5);
    }

    private DefaultSearchService service(RecordingRetriever retriever, SearchAgent agent) {
        return service(new FakeEmbeddingProvider(768), retriever, agent);
    }

    /** Hand-wired with the shipped defaults. */
    private DefaultSearchService service(FakeEmbeddingProvider embeddings, RecordingRetriever retriever,
                                         SearchAgent agent) {
        DefaultSearchService svc = new DefaultSearchService(
                new QueryEmbedder(embeddings, 0), retriever,
                new NoopReranker(), new EntityGrouper(3, 0.1),
                new RecencyBoost(FieldSets.bundled(), 0.1, 14, Clock.systemUTC()), collapser(), agent);
        svc.maxTopK = 100;
        svc.candidateMultiplier = 10;
        svc.minCandidates = 100;
        svc.maxCandidates = 500;
        return svc;
    }

    private static SearchQuery ask(int topK, boolean answer) {
        return new SearchQuery("holidays", List.of(), Map.of(), topK, SearchQuery.Mode.HYBRID, answer, null, false);
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
    void overFetchesChunkCandidatesForEntitiesToBeFoundIn() {
        RecordingRetriever small = new RecordingRetriever();
        service(small, new StubSearchAgent("")).search(ask(10, false));
        assertEquals(100, small.lastLimit, "topK 10 x multiplier 10");

        RecordingRetriever larger = new RecordingRetriever();
        service(larger, new StubSearchAgent("")).search(ask(20, false));
        assertEquals(200, larger.lastLimit);
    }

    /**
     * The pool reaches OpenSearch as both {@code size} and knn {@code k}, so an unbounded value is a
     * request for an unbounded result set. A non-positive topK previously produced a negative
     * {@code size} with no validation anywhere on the path.
     */
    @Test
    void holdsTheCandidatePoolBetweenItsFloorAndCeiling() {
        RecordingRetriever high = new RecordingRetriever();
        service(high, new StubSearchAgent("")).search(ask(5_000, false));
        assertEquals(500, high.lastLimit, "topK clamped to 100, and 100 x 10 held to max-candidates");

        RecordingRetriever low = new RecordingRetriever();
        service(low, new StubSearchAgent("")).search(ask(0, false));
        assertEquals(100, low.lastLimit, "0 is raised to 1, and a tiny pool raised to min-candidates");
    }

    /**
     * The reported symptom: ten results that were two job postings shown five times each. Chunks of one
     * entity must fold into one result, leaving the remaining slots to other entities.
     */
    @Test
    void oneEntityCannotFillEveryResultSlotWithItsOwnChunks() {
        RecordingRetriever retriever = new RecordingRetriever();
        List<SearchHit> pool = new ArrayList<>();
        double score = 1.0;
        for (int i = 0; i < 5; i++) {
            pool.add(new SearchHit("jobA_" + i, "jobA", "kn_1", i, "Backend Engineer", "a" + i, "s", "u",
                    score -= 0.01, Map.of()));
        }
        pool.add(new SearchHit("jobB_0", "jobB", "kn_1", 0, "Platform Engineer", "b0", "s", "u",
                score - 0.01, Map.of()));
        retriever.result = pool;

        SearchResponse response = service(retriever, new StubSearchAgent("")).search(ask(10, false));

        assertEquals(List.of("jobA", "jobB"), response.hits().stream().map(SearchHit::entityId).toList());
        assertEquals(2, response.hits().get(0).moreMatches().size(), "capped at 3 chunks per result");
    }

    @Test
    void skipsTheQueryEmbeddingForAPurelyLexicalSearch() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        DefaultSearchService svc = service(embeddings, new RecordingRetriever(), new StubSearchAgent(""));

        svc.search(new SearchQuery("holidays", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL, false, null, false));

        assertEquals(0, embeddings.embedCalls, "a lexical search must not pay for an embedding");

        svc.search(ask(10, false));
        assertTrue(embeddings.embedCalls > 0, "hybrid and semantic do need one");
    }

    /**
     * The failure this exists for: a spent embedding quota refuses the query vector, and that used to
     * propagate as a 500 although the lexical leg could run perfectly well. Indexing sharing the same
     * quota made it the common case, not an edge.
     */
    @Test
    void aRefusedQueryEmbeddingFallsBackToLexicalAndReportsWhy() {
        FakeEmbeddingProvider throttled = new FakeEmbeddingProvider(768);
        throttled.rateLimitedUntil = Instant.now().plusSeconds(60);
        RecordingRetriever retriever = new RecordingRetriever();
        retriever.result = List.of(hit("ent_1_0"));

        SearchResponse response = service(throttled, retriever, new StubSearchAgent("")).search(ask(10, false));

        assertEquals(1, response.hits().size(), "the lexical leg's hits must survive");
        assertEquals(SearchQuery.Mode.LEXICAL, retriever.lastMode,
                "a hybrid retrieval cannot run on a null vector, so the query must be retrieved lexically");
        assertNotNull(response.vectorError());
    }

    @Test
    void aSuccessfulSearchReportsNoVectorError() {
        RecordingRetriever retriever = new RecordingRetriever();

        SearchResponse response = service(retriever, new StubSearchAgent("")).search(ask(10, false));

        assertEquals(SearchQuery.Mode.HYBRID, retriever.lastMode);
        assertNull(response.vectorError());
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
                SearchQuery.Mode.HYBRID, false, null, true);

        SearchResponse response = service(retriever, new StubSearchAgent("")).search(collapsing);

        assertEquals(List.of("a", "c"),
                response.hits().stream().map(SearchHit::chunkId).toList());
    }
}
