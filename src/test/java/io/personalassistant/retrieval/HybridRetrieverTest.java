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
        return retriever;
    }

    @Test
    void rrfRewardsChunksRankedHighlyByBothMethods() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        // "B" is top of lexical and second in vector; "A" tops vector only; "C" mid both.
        index.lexicalResult = List.of(hit("B"), hit("C"), hit("A"));
        index.vectorResult = List.of(hit("A"), hit("B"), hit("C"));

        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 3, SearchQuery.Mode.HYBRID, false, null, false);
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
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 5, SearchQuery.Mode.LEXICAL, false, null, false);
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
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 2, SearchQuery.Mode.HYBRID, false, null, false);

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
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 2, SearchQuery.Mode.HYBRID, false, null, false);

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

    /** The fused score cannot say which leg put a chunk where it is; the recorded ranks can. */
    @Test
    void hybridRecordsWhereEachLegRankedAChunk() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("B"), hit("C"), hit("A"));
        index.vectorResult = List.of(hit("A"), hit("B"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 3, SearchQuery.Mode.HYBRID, false, null, false);

        Map<String, SearchHit> byId = new java.util.HashMap<>();
        retriever(index).retrieve(query, new float[] {0f}, 3).forEach(h -> byId.put(h.chunkId(), h));

        assertEquals(1, byId.get("B").ranking().lexicalRank());
        assertEquals(2, byId.get("B").ranking().vectorRank());
        assertEquals(2, byId.get("C").ranking().lexicalRank());
        assertEquals(null, byId.get("C").ranking().vectorRank(), "the meaning leg never returned C");
        assertEquals(byId.get("B").score(), byId.get("B").ranking().retrievalScore(), 1e-12);
    }

    @Test
    void aSingleLegModeRecordsOnlyItsOwnRank() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("A"), hit("B"));
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 5, SearchQuery.Mode.LEXICAL, false, null, false);

        SearchHit second = retriever(index).retrieve(query, null, 5).get(1);

        assertEquals(2, second.ranking().lexicalRank());
        assertEquals(null, second.ranking().vectorRank());
    }

    /**
     * The retriever hands back every chunk it fetched. A per-entity cap here used to trim the pool it had
     * already sized, starving the result set; grouping into entities is {@link EntityGrouper}'s job.
     */
    @Test
    void returnsChunksUncappedEvenWhenOneEntityDominates() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("a0", "big"), hit("a1", "big"), hit("a2", "big"), hit("b0", "other"));
        SearchQuery oneEach = new SearchQuery("q", List.of(), Map.of(), 10, SearchQuery.Mode.LEXICAL,
                false, 1, false);

        assertEquals(4, retriever(index).retrieve(oneEach, null, 10).size());
    }
}
