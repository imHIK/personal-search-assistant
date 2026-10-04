package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.FacetValue;
import java.util.List;
import java.util.Map;

/** Browsing entities across knowledges, and the user's own marks on them. */
public interface EntityService {

    /** @param limit the page size actually applied, after clamping */
    record Page(List<Entity> items, long total, int limit, int offset) {}

    Page query(EntityFilter filter, int limit, int offset);

    /** Counted over the filter's scope only, so picking a value never hides the others. */
    Map<String, List<FacetValue>> facets(EntityFilter filter, List<String> paths, int limitPerPath);

    /**
     * @throws IllegalArgumentException on a bad key or a non-scalar value
     * @throws java.util.NoSuchElementException if no such entity
     */
    Entity mergeCustom(String id, Map<String, Object> values);
}
