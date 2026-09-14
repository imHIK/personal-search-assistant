package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion over any number of ranked lists.
 *
 * <p>RRF scores a document by {@code weight / (k + rank)} summed across the lists that returned it, so
 * agreement between lists beats a strong showing in one. That property is why it is used twice here for
 * different reasons: fusing the lexical and vector legs of one query, where the two score scales (BM25
 * and cosine) cannot be compared directly; and fusing the results of several queries derived from one
 * document, where the scales <em>are</em> comparable but a posting matching three facets should still
 * outrank one that matches a single facet very well.
 *
 * <p>Extracted so there is one implementation. Two copies would drift, and the constant {@code k} has to
 * mean the same thing in both for the tuning advice in {@code application.properties} to hold.
 */
final class Rrf {

    private Rrf() {
    }

    /**
     * Fuse {@code lists} into one ranking of at most {@code limit} hits.
     *
     * @param weights per-list multiplier; a shorter list than {@code lists} defaults the remainder to 1.0
     * @param rrfK    rank-smoothing constant — larger flattens the curve so agreement matters more
     */
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
