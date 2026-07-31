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
        return new SearchHit(chunkId, "ent", "kn", "t", "s", "u", 0.0, Map.of());
    }

    @Test
    void rrfRewardsChunksRankedHighlyByBothMethods() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        // "B" is top of lexical and second in vector; "A" tops vector only; "C" mid both.
        index.lexicalResult = List.of(hit("B"), hit("C"), hit("A"));
        index.vectorResult = List.of(hit("A"), hit("B"), hit("C"));

        HybridRetriever retriever = new HybridRetriever(index);
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 3, SearchQuery.Mode.HYBRID, false);
        List<SearchHit> fused = retriever.retrieve(query, new float[] {0f}, 3);

        assertEquals(3, fused.size());
        assertEquals("B", fused.get(0).chunkId(), "chunk ranked highly by both should fuse to the top");
        assertTrue(fused.get(0).score() >= fused.get(1).score());
        assertTrue(fused.get(1).score() >= fused.get(2).score());
    }

    @Test
    void lexicalModeDelegatesDirectly() {
        RecordingSearchIndex index = new RecordingSearchIndex();
        index.lexicalResult = List.of(hit("A"), hit("B"));
        HybridRetriever retriever = new HybridRetriever(index);
        SearchQuery query = new SearchQuery("q", List.of(), Map.of(), 5, SearchQuery.Mode.LEXICAL, false);
        assertEquals(2, retriever.retrieve(query, null, 5).size());
    }
}
