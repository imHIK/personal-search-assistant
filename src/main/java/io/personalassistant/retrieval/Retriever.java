package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;

public interface Retriever {

    /** @param queryVector null for purely lexical retrieval */
    List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit);
}
