package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores a hit by the sum of {@code weight / (k + rank)} over the lists that returned it, so agreement beats
 * a single strong showing.
 */
final class Rrf {

    private Rrf() {
    }

    /** @param weights a list shorter than {@code lists} defaults the rest to 1.0 */
    static List<SearchHit> fuse(List<List<SearchHit>> lists, List<Double> weights, int rrfK, int limit) {
        Map<String, SearchHit> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (int i = 0; i < lists.size(); i++) {
            double weight = i < weights.size() ? weights.get(i) : 1.0;
            List<SearchHit> hits = lists.get(i);
            if (hits == null) {
                continue;
            }
            for (int rank = 0; rank < hits.size(); rank++) {
                SearchHit hit = hits.get(rank);
                byId.putIfAbsent(hit.chunkId(), hit);
                scores.merge(hit.chunkId(), weight / (rrfK + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }
}
