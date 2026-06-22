package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Default reranker: pass-through that just trims to topK. Swap in a cross-encoder
 * implementation later for real precision gains, without touching callers.
 */
@ApplicationScoped
public class NoopReranker implements Reranker {

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        return candidates.size() <= topK ? candidates : List.copyOf(candidates.subList(0, topK));
    }
}
