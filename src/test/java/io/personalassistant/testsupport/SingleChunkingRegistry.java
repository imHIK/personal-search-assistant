package io.personalassistant.testsupport;

import io.personalassistant.indexing.chunking.ChunkingStrategy;
import io.personalassistant.indexing.chunking.ChunkingStrategyRegistry;
import java.util.Set;

/** Registry that always returns a single strategy, for indexing tests that don't vary the chunker. */
public class SingleChunkingRegistry implements ChunkingStrategyRegistry {

    private final ChunkingStrategy strategy;

    public SingleChunkingRegistry(ChunkingStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public ChunkingStrategy get(String name) {
        return strategy;
    }

    @Override
    public String defaultName() {
        return strategy.name();
    }

    @Override
    public Set<String> names() {
        return Set.of(strategy.name());
    }
}
