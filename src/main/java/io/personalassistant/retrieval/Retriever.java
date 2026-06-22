package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;

/**
 * Turns a query into a ranked candidate list. The hybrid implementation runs lexical
 * (BM25) and semantic (k-NN) retrieval and fuses them (e.g. Reciprocal Rank Fusion).
 * Hides the fusion strategy and the underlying {@code SearchIndex} from the service layer.
 */
public interface Retriever {

    /**
     * @param query       the parsed request (mode, filters, sourceIds)
     * @param queryVector the embedded query, or null for purely lexical retrieval
     * @param limit       number of candidates to return (before reranking)
     */
    List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit);
}
