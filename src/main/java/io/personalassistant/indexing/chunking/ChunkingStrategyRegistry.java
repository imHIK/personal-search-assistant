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

    /**
     * As {@link #get(String)}, but allowing a strategy that declares {@link ChunkingStrategy#prefers} for
     * {@code contentType} to be chosen when {@code name} is merely the inherited global default. An
     * explicit per-knowledge choice always wins; a {@code null} content type behaves like
     * {@link #get(String)}.
     */
    default ChunkingStrategy get(String name, String contentType) {
        return get(name);
    }

    /** Name of the strategy used when a knowledge has not chosen one (or chose an unknown one). */
    String defaultName();

    /** All registered strategy names (for validation / surfacing the available options). */
    Set<String> names();
}
