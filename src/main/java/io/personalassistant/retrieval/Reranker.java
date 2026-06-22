package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.List;

/**
 * Reorders candidate hits for final precision, typically with a cross-encoder that scores
 * (query, chunk) pairs directly. Optional: a no-op implementation passes hits through.
 */
public interface Reranker {

    List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK);
}
