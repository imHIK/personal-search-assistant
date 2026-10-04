package io.personalassistant.app;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.FacetValue;
import io.personalassistant.domain.service.EntityService;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@ApplicationScoped
public class DefaultEntityService implements EntityService {

    static final int MAX_PAGE = 200;
    static final int MAX_FACET_VALUES = 500;
    private static final Pattern CUSTOM_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final EntityRepository entities;

    @Inject
    public DefaultEntityService(EntityRepository entities) {
        this.entities = entities;
    }

    @Override
    public Page query(EntityFilter filter, int limit, int offset) {
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        int from = Math.max(0, offset);
        return new Page(entities.findMatching(filter, size, from), entities.countMatching(filter), size, from);
    }

    @Override
    public Map<String, List<FacetValue>> facets(EntityFilter filter, List<String> paths, int limitPerPath) {
        paths.forEach(EntityFilter::requireFilterable);
        return entities.facets(filter.scopeOnly(), paths, Math.max(1, Math.min(MAX_FACET_VALUES, limitPerPath)));
    }

    @Override
    public Entity mergeCustom(String id, Map<String, Object> values) {
        values.forEach((key, value) -> {
            if (key == null || !CUSTOM_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("\"" + key + "\" is not a valid key: a letter followed by "
                        + "letters, digits or _");
            }
            if (value != null && !(value instanceof String || value instanceof Number
                    || value instanceof Boolean)) {
                throw new IllegalArgumentException("\"" + key + "\" must be text, a number, true/false or "
                        + "null to remove it");
            }
        });
        if (!entities.mergeCustom(id, values)) {
            throw new NoSuchElementException("No entity with id " + id);
        }
        return entities.findById(id).orElseThrow(() -> new NoSuchElementException("No entity with id " + id));
    }
}
