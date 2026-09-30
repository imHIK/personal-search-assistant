package io.personalassistant.domain.service;

import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;

public interface SearchService {

    SearchResponse search(SearchQuery query);
}
