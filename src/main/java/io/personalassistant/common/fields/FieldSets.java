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
 * Named lists of metadata field names, optionally scoped per connector, loaded from
 * {@code config/field-sets.json}.
 *
 * <p><strong>Why named sets rather than constants or a flat property.</strong> "Which metadata fields
 * matter here" comes up in more than one place — what gets prefixed before embedding, what locates a
 * source in a prompt — and the right answer differs per place <em>and</em> per connector: a Gmail chunk
 * is best identified by sender, a Drive document by its heading path. A hardcoded list can express
 * neither, and a single flat property can express only one of the two.
 *
 * <p>That is not hypothetical. The prompt's locator list was a Java constant reading
 * {@code ("sheet", "page", "headingPath")} — and {@code sheet} and {@code page} are produced by nothing
 * in the pipeline, while {@code rowRange}, the one structural locator that <em>is</em> produced, was
 * missing and so never reached a prompt. A constant nobody had reason to look at hid that for as long as
 * it existed. Naming the set and giving it a file with a description is what makes such a mismatch
 * reviewable.
 *
 * <p>Resolution is whole-value tier selection, mirroring {@code ScheduleResolver}: the per-connector list
 * wins entire if present, otherwise the default. Lists are not merged — a connector that overrides is
 * stating the whole answer, which is easier to reason about than a union.
 *
 * <p>Unknown set names warn and return empty rather than throwing, following
 * {@code CdiChunkingStrategyRegistry}: a missing field set degrades retrieval slightly, and taking
 * indexing offline over it would be the worse outcome.
 */
@ApplicationScoped
public class FieldSets {

    private static final Logger LOG = Logger.getLogger(FieldSets.class.getName());

    static final String RESOURCE = "config/field-sets.json";

    /** Metadata prefixed to chunk text before embedding. Changing its contents needs a re-index. */
    public static final String EMBED_CONTEXT = "embedContext";

    /** Metadata shown in a grounded prompt's source header so an answer can cite a location. */
    public static final String PROMPT_LOCATOR = "promptLocator";

    /** Optional filesystem path replacing the bundled file wholesale; blank counts as absent. */
    @ConfigProperty(name = "app.field-sets.path")
    Optional<String> overridePath;

    private Map<String, Scoped> sets = Map.of();

    /** One named set: a default list plus optional whole-list overrides per connector. */
    private record Scoped(List<String> byDefault, Map<SourceType, List<String>> bySourceType) {}

    /** The bundled file with no override, loaded eagerly. For tests and tooling without CDI. */
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
     * The field list for {@code setName} under {@code sourceType}.
     *
     * @param sourceType the connector the chunk came from; null falls back to the default list
     * @return the resolved list, never null; empty when the set is unknown or defines nothing
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

    /** The unscoped list for {@code setName}. */
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
     * An unrecognised connector name is a warning, not a failure: it is almost always a set written for a
     * connector that has not shipped yet, and failing startup over it would block adding config ahead of
     * code. A typo degrades to the default list, which the warning names.
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
