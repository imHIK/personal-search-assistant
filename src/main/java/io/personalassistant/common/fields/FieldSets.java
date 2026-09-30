package io.personalassistant.common.fields;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.JsonConfigLoader;
import io.personalassistant.domain.model.enums.SourceType;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Named metadata field lists from {@code config/field-sets.json}. A per-connector list wins entire over the
 * default; lists are never merged. An unknown set name warns and resolves empty.
 */
@ApplicationScoped
public class FieldSets {

    private static final Logger LOG = Logger.getLogger(FieldSets.class.getName());

    static final String RESOURCE = "config/field-sets.json";

    /** Changing its contents needs a re-index. */
    public static final String EMBED_CONTEXT = "embedContext";

    public static final String PROMPT_LOCATOR = "promptLocator";

    public static final String RECENCY = "recency";

    @ConfigProperty(name = "app.field-sets.path")
    Optional<String> overridePath;

    private Map<String, Scoped> sets = Map.of();

    private record Scoped(List<String> byDefault, Map<SourceType, List<String>> bySourceType) {}

    public static FieldSets bundled() {
        FieldSets fieldSets = new FieldSets();
        fieldSets.overridePath = Optional.empty();
        fieldSets.load();
        return fieldSets;
    }

    void onStart(@Observes StartupEvent event) {
        load();
    }

    void load() {
        JsonNode root = JsonConfigLoader.load(RESOURCE, overridePath);
        JsonNode node = root.path("fieldSets");
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " must define at least one entry under \"fieldSets\"");
        }
        Map<String, Scoped> parsed = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> parsed.put(entry.getKey(), readSet(entry.getKey(), entry.getValue())));
        this.sets = Map.copyOf(parsed);
        LOG.info("Field sets loaded: " + sets.keySet());
    }

    /**
     * @param sourceType null falls back to the default list
     * @return never null; empty when the set is unknown
     */
    public List<String> resolve(String setName, SourceType sourceType) {
        Scoped scoped = sets.get(setName);
        if (scoped == null) {
            LOG.warning("Unknown field set \"" + setName + "\" (available: " + sets.keySet()
                    + "); using no fields");
            return List.of();
        }
        if (sourceType != null) {
            List<String> scopedFields = scoped.bySourceType().get(sourceType);
            if (scopedFields != null) {
                return scopedFields;
            }
        }
        return scoped.byDefault();
    }

    public List<String> resolve(String setName) {
        return resolve(setName, null);
    }

    public Set<String> setNames() {
        return sets.keySet();
    }

    private Scoped readSet(String name, JsonNode node) {
        List<String> byDefault = textList(node.path("default"));
        Map<SourceType, List<String>> bySourceType = new LinkedHashMap<>();
        JsonNode scoped = node.path("bySourceType");
        if (scoped.isObject()) {
            scoped.fields().forEachRemaining(entry -> {
                SourceType type = parseSourceType(entry.getKey(), name);
                if (type != null) {
                    bySourceType.put(type, textList(entry.getValue()));
                }
            });
        }
        return new Scoped(byDefault, Map.copyOf(bySourceType));
    }

    /**
     * An unknown connector name warns rather than failing startup: it is usually a set written ahead of the
     * connector.
     */
    private static SourceType parseSourceType(String raw, String setName) {
        try {
            return SourceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            LOG.warning("Field set \"" + setName + "\" scopes fields to unknown source type \"" + raw
                    + "\"; that entry is ignored. Known types: " + List.of(SourceType.values()));
            return null;
        }
    }

    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) {
                out.add(v.asText().trim());
            }
        });
        return List.copyOf(out);
    }
}
