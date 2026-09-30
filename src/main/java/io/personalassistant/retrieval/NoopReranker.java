package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** A pass-through that trims to topK; a real reranker replaces this bean. */
@ApplicationScoped
public class NoopReranker implements Reranker {

    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        return candidates.size() <= topK ? candidates : List.copyOf(candidates.subList(0, topK));
    }
}
