package io.personalassistant.indexing.chunking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Discovers all {@link ChunkingStrategy} beans via CDI and indexes them by {@link ChunkingStrategy#name()}.
 * The default is {@code app.chunking.strategy} (falling back to {@code recursive} if that name isn't
 * registered), so a fresh install and any knowledge that hasn't customised chunking both get the
 * recommended recursive splitter.
 */
@ApplicationScoped
public class CdiChunkingStrategyRegistry implements ChunkingStrategyRegistry {

    private static final Logger LOG = Logger.getLogger(CdiChunkingStrategyRegistry.class.getName());

    private final Map<String, ChunkingStrategy> byName;
    private final List<ChunkingStrategy> all;
    private final String defaultName;

    /**
     * Whether a strategy may be chosen by content type when the knowledge did not ask for a specific
     * one. Off restores name-only selection.
     */
    @ConfigProperty(name = "app.chunking.mime-aware", defaultValue = "true")
    boolean mimeAware;

    @Inject
    public CdiChunkingStrategyRegistry(
            Instance<ChunkingStrategy> strategies,
            @ConfigProperty(name = "app.chunking.strategy", defaultValue = RecursiveCharacterChunkingStrategy.NAME)
            String configuredDefault) {
        this(strategies.stream().toList(), configuredDefault);
    }

    /** Package-private for unit tests: build directly from a list of strategies. */
    CdiChunkingStrategyRegistry(List<ChunkingStrategy> strategies, String configuredDefault) {
        Map<String, ChunkingStrategy> map = new HashMap<>();
        for (ChunkingStrategy strategy : strategies) {
            map.put(strategy.name(), strategy);
        }
        this.byName = Map.copyOf(map);
        this.all = List.copyOf(strategies);
        this.mimeAware = true;
        if (map.containsKey(configuredDefault)) {
            this.defaultName = configuredDefault;
        } else {
            LOG.warning("Configured default chunking strategy '" + configuredDefault
                    + "' is not registered; using '" + RecursiveCharacterChunkingStrategy.NAME + "'");
            this.defaultName = RecursiveCharacterChunkingStrategy.NAME;
        }
    }

    @Override
    public ChunkingStrategy get(String name) {
        if (name != null && byName.containsKey(name)) {
            return byName.get(name);
        }
        if (name != null && !name.isBlank()) {
            LOG.warning("Unknown chunking strategy '" + name + "'; falling back to default '" + defaultName + "'");
        }
        ChunkingStrategy fallback = byName.get(defaultName);
        if (fallback == null) {
            throw new IllegalStateException("No chunking strategies registered (default '" + defaultName + "' missing)");
        }
        return fallback;
    }

    /**
     * The strategy for {@code name}, letting content type break the tie when {@code name} is only the
     * global default — i.e. when no knowledge-level setting asked for anything in particular.
     *
     * <p>The distinction matters: selection used to be keyed purely on a per-knowledge string, so a
     * spreadsheet sitting in a knowledge of mostly prose was chunked as prose. An explicit per-knowledge
     * choice still wins, so this can never override a deliberate decision — only an inherited default.
     */
    @Override
    public ChunkingStrategy get(String name, String contentType) {
        if (mimeAware && contentType != null && (name == null || name.equals(defaultName))) {
            for (ChunkingStrategy strategy : all) {
                if (strategy.prefers(contentType)) {
                    LOG.fine(() -> "Chunking " + contentType + " with '" + strategy.name()
                            + "' (preferred over the default '" + defaultName + "')");
                    return strategy;
                }
            }
        }
        return get(name);
    }

    @Override
    public String defaultName() {
        return defaultName;
    }

    @Override
    public Set<String> names() {
        return byName.keySet();
    }
}
