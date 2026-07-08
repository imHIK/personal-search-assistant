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
    private final String defaultName;

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

    @Override
    public String defaultName() {
        return defaultName;
    }

    @Override
    public Set<String> names() {
        return byName.keySet();
    }
}
