package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import java.util.List;

/** Reorders candidates for final precision, e.g. a cross-encoder scoring (query, chunk) pairs. */
public interface Reranker {

    List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK);
}
