package io.personalassistant.domain.service;

import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;

/**
 * Entry point for the read path. Coordinates query embedding, retrieval, reranking,
 * and (optionally) agentic answer synthesis.
 */
public interface SearchService {

    /**
     * Execute a search.
     *
     * @param query the parsed request
     * @return ranked hits and, if requested, a grounded answer
     */
    SearchResponse search(SearchQuery query);
}
