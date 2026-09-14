package io.personalassistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.testsupport.RecordingSearchIndex;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridRetrieverTest {

    private static SearchHit hit(String chunkId) {
        return hit(chunkId, "ent");
    }

    private static SearchHit hit(String chunkId, String entityId) {
        return new SearchHit(chunkId, entityId, "kn", 0, "t", "full text", "s", "u", 0.0, Map.of());
    }

    /** Config fields are injected in production; a hand-wired retriever sets the shipped defaults. */
    private static HybridRetriever retriever(RecordingSearchIndex index) {
        HybridRetriever retriever = new HybridRetriever(index);
        retriever.rrfK = 60;
        retriever.lexicalWeight = 1.0;
        retriever.vectorWeight = 1.0;
        retriever.maxChunksPerEntity = 0;
        return retriever;
    }

    @Test
    void rrfRewardsChunksRankedHighlyByBothMethods() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        // "B" is top of lexical and second in vector; "A" tops vector only; "C" mid both.
        index.lexicalResult = List.of(hit("B"), hit("C"), hit("A"));
        index.vectorResult = List.of(hit("A"), hit("B"), hit("C"));

        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 3, SearchQuery.Mode.HYBRID, false, null, false, null);
        List<SearchHit> fused = retriever(index).retrieve(query, new float[] {0f}, 3);

        assertEquals(3, fused.size());
        assertEquals("B", fused.get(0).chunkId(), "chunk ranked highly by both should fuse to the top");
        assertTrue(fused.get(0).score() >= fused.get(1).score());
        assertTrue(fused.get(1).score() >= fused.get(2).score());
    }

    @Test
    void lexicalModeDelegatesDirectly() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("A"), hit("B"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 5, SearchQuery.Mode.LEXICAL, false, null, false, null);
        assertEquals(2, retriever(index).retrieve(query, null, 5).size());
    }

    /**
     * Equal weighting assumes both legs are equally trustworthy for every query. On a conversational
     * query the BM25 leg matches incidental words and contributes a run of irrelevant candidates that can
     * outrank the vector leg's correct ones, so the weight is the knob for discounting it.
     */
    @Test
    void perLegWeightsShiftWhichLegWins() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("lex-only"));
        index.vectorResult = List.of(hit("vec-only"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 2, SearchQuery.Mode.HYBRID, false, null, false, null);

        HybridRetriever trustLexical = retriever(index);
        trustLexical.vectorWeight = 0.1;
        assertEquals("lex-only", trustLexical.retrieve(query, new float[] {0f}, 2).get(0).chunkId());

        HybridRetriever trustVector = retriever(index);
        trustVector.lexicalWeight = 0.1;
        assertEquals("vec-only", trustVector.retrieve(query, new float[] {0f}, 2).get(0).chunkId());
    }

    @Test
    void rrfKChangesHowMuchTopRanksDominate() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("A"), hit("B"));
        index.vectorResult = List.of(hit("B"), hit("A"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 2, SearchQuery.Mode.HYBRID, false, null, false, null);

        HybridRetriever flat = retriever(index);
        flat.rrfK = 1000;
        HybridRetriever peaked = retriever(index);
        peaked.rrfK = 1;

        double flatGap = gap(flat.retrieve(query, new float[] {0f}, 2));
        double peakedGap = gap(peaked.retrieve(query, new float[] {0f}, 2));
        assertTrue(peakedGap >= flatGap, "a smaller k must not flatten the score spread");
    }

    private static double gap(List<SearchHit> hits) {
        return hits.get(0).score() - hits.get(hits.size() - 1).score();
    }

    /**
     * Off by default on purpose: a cap buys source diversity but harms the case where the right answer
     * <em>is</em> many chunks of one document, which is the query class that motivated this work.
     */
    @Test
    void perEntityCapIsOffByDefaultAndTrimsWhenTurnedOn() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("a0", "big"), hit("a1", "big"), hit("a2", "big"), hit("b0", "other"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL, false, null, false, null);

        assertEquals(4, retriever(index).retrieve(query, null, 10).size(), "unlimited by default");

        HybridRetriever capped = retriever(index);
        capped.maxChunksPerEntity = 2;
        List<SearchHit> trimmed = capped.retrieve(query, null, 10);
        assertEquals(3, trimmed.size());
        assertEquals(List.of("a0", "a1", "b0"),
                trimmed.stream().map(SearchHit::chunkId).toList(),
                "rank order is preserved; only the over-quota chunks drop out");
    }

    @Test
    void aPerRequestCapOverridesTheGlobalOne() {
        // "one result per record" for a posting corpus, without forcing whole-document chunking.
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("a0", "job1"), hit("a1", "job1"), hit("b0", "job2"));
        SearchQuery oneEach = new SearchQuery("q", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL,
                false, 1, false, null);

        List<SearchHit> capped = retriever(index).retrieve(oneEach, null, 10);

        assertEquals(List.of("a0", "b0"), capped.stream().map(SearchHit::chunkId).toList());
    }

    @Test
    void aPerRequestZeroLiftsAConfiguredGlobalCap() {
        // 0 means unlimited, and an explicit override must win even when the global is restrictive —
        // otherwise a caller could tighten the cap but never loosen it.
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("a0", "big"), hit("a1", "big"), hit("a2", "big"));
        HybridRetriever retriever = retriever(index);
        retriever.maxChunksPerEntity = 1;
        SearchQuery unlimited = new SearchQuery("q", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL,
                false, 0, false, null);

        assertEquals(3, retriever.retrieve(unlimited, null, 10).size());
    }
}
