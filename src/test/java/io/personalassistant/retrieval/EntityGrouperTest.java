package io.personalassistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntityGrouperTest {

    private final EntityGrouper grouper = new EntityGrouper(3, 0.1);

    private static SearchHit chunk(String entityId, int ordinal, double score) {
        return new SearchHit(entityId + "_" + ordinal, entityId, "kn", ordinal, "Title " + entityId,
                "text of " + entityId + " chunk " + ordinal, "snippet " + ordinal, "u", score, Map.of());
    }

    private static List<String> entities(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::entityId).toList();
    }

    @Test
    void siblingsFloodingThePoolStillYieldOneResultPerEntity() {
        List<SearchHit> pool = new ArrayList<>();
        double score = 1.0;
        for (int i = 0; i < 7; i++) {
            pool.add(chunk("jobA", i, score -= 0.01));
        }
        for (int i = 0; i < 7; i++) {
            pool.add(chunk("jobB", i, score -= 0.01));
        }
        pool.add(chunk("jobC", 0, score - 0.01));

        List<SearchHit> results = grouper.group(pool, null);

        assertEquals(List.of("jobA", "jobB", "jobC"), entities(results));
    }

    @Test
    void furtherMatchesAreTheEntitysNextBestChunksBestFirst() {
        List<SearchHit> results = grouper.group(List.of(
                chunk("doc", 4, 0.9), chunk("doc", 1, 0.5), chunk("doc", 2, 0.7), chunk("doc", 0, 0.3)), null);

        SearchHit doc = results.get(0);
        assertEquals("doc_4", doc.chunkId(), "the best chunk represents the entity");
        assertEquals(List.of("doc_2", "doc_1"),
                doc.moreMatches().stream().map(SearchHit.Match::chunkId).toList(),
                "capped at 3 chunks per result, the rest best first");
    }

    @Test
    void aCapOfOneReportsTheBestChunkAloneAndScoresOnItAlone() {
        List<SearchHit> results = grouper.group(List.of(chunk("doc", 0, 0.9), chunk("doc", 1, 0.8)), 1);

        assertTrue(results.get(0).moreMatches().isEmpty());
        assertEquals(0.9, results.get(0).score(), 1e-9);
    }

    @Test
    void aCapOfZeroKeepsEveryMatchingChunk() {
        List<SearchHit> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pool.add(chunk("table", i, 1.0 - i * 0.1));
        }

        assertEquals(5, grouper.group(pool, 0).get(0).moreMatches().size());
    }

    @Test
    void breadthBreaksANearTieButCannotBeatAClearlyBetterMatch() {
        List<SearchHit> nearTie = grouper.group(List.of(
                chunk("single", 0, 1.0),
                chunk("broad", 0, 0.95), chunk("broad", 1, 0.9), chunk("broad", 2, 0.9)), null);
        assertEquals(List.of("broad", "single"), entities(nearTie),
                "0.95 + 0.1 x 1.8 beats a lone 1.0");

        List<SearchHit> clearWinner = grouper.group(List.of(
                chunk("single", 0, 1.0),
                chunk("broad", 0, 0.5), chunk("broad", 1, 0.49), chunk("broad", 2, 0.48)), null);
        assertEquals(List.of("single", "broad"), entities(clearWinner));
    }

    @Test
    void hitsWithoutAnEntityAreNeverPooledTogether() {
        SearchHit a = new SearchHit("a", null, "kn", 0, "t", "x", "s", "u", 0.9, Map.of());
        SearchHit b = new SearchHit("b", null, "kn", 0, "t", "y", "s", "u", 0.8, Map.of());

        assertEquals(2, grouper.group(List.of(a, b), null).size());
    }

    @Test
    void groundingTextJoinsEveryMatchInDocumentOrder() {
        SearchHit doc = grouper.group(List.of(
                chunk("doc", 3, 0.9), chunk("doc", 1, 0.8), chunk("doc", 2, 0.7)), null).get(0);

        String grounding = doc.groundingText();
        assertTrue(grounding.indexOf("chunk 1") < grounding.indexOf("chunk 2"));
        assertTrue(grounding.indexOf("chunk 2") < grounding.indexOf("chunk 3"));
    }

    @Test
    void theRankingKeepsTheRetrievalScoreAndRecordsTheBonus() {
        SearchHit doc = grouper.group(List.of(chunk("doc", 0, 0.9), chunk("doc", 1, 0.5)), null).get(0);

        assertEquals(0.9, doc.ranking().retrievalScore(), 1e-9);
        assertEquals(0.95, doc.ranking().groupedScore(), 1e-9);
        assertEquals(0.95, doc.score(), 1e-9);
    }

    @Test
    void emptyInputGroupsToNothing() {
        assertEquals(List.of(), grouper.group(List.of(), null));
        assertEquals(List.of(), grouper.group(null, null));
    }
}
