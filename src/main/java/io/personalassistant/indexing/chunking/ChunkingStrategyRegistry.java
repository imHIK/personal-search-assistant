package io.personalassistant.indexing.chunking;

import java.util.Set;

/**
 * Selects a {@link ChunkingStrategy} by name. New strategies register themselves (one CDI bean each)
 * and become available for a knowledge to choose. Resolution never hard-fails: an unknown or unset
 * name falls back to the configured default so a bad setting degrades to sensible chunking rather
 * than breaking indexing.
 */
public interface ChunkingStrategyRegistry {

    /** The strategy for {@code name}, or the default strategy when {@code name} is null/blank/unknown. */
    ChunkingStrategy get(String name);

    /** Name of the strategy used when a knowledge has not chosen one (or chose an unknown one). */
    String defaultName();

    /** All registered strategy names (for validation / surfacing the available options). */
    Set<String> names();
}
