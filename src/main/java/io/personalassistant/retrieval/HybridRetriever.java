package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default retriever. For HYBRID mode it runs lexical and vector retrieval independently and
 * merges them with Reciprocal Rank Fusion (RRF) — which avoids reconciling BM25 and cosine
 * score scales. LEXICAL/SEMANTIC delegate to a single primitive.
 */
@ApplicationScoped
public class HybridRetriever implements Retriever {

    private static final int RRF_K = 60;

    private final SearchIndex index;

    @Inject
    public HybridRetriever(SearchIndex index) {
        this.index = index;
    }

    @Override
    public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
        return switch (query.mode()) {
            case LEXICAL -> index.lexicalSearch(query, limit);
            case SEMANTIC -> index.vectorSearch(query, queryVector, limit);
            case HYBRID -> fuse(
                    index.lexicalSearch(query, limit),
                    index.vectorSearch(query, queryVector, limit),
                    limit);
        };
    }

    private List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> vector, int limit) {
        Map<String, SearchHit> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        accumulate(lexical, byId, scores);
        accumulate(vector, byId, scores);
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> byId.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }

    private void accumulate(List<SearchHit> hits, Map<String, SearchHit> byId, Map<String, Double> scores) {
        for (int rank = 0; rank < hits.size(); rank++) {
            SearchHit h = hits.get(rank);
            byId.putIfAbsent(h.chunkId(), h);
            scores.merge(h.chunkId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }
}
