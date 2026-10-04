package io.personalassistant.indexing.chunking;

import java.util.Set;

/** Never hard-fails: an unknown or unset name falls back to the default. */
public interface ChunkingStrategyRegistry {

    ChunkingStrategy get(String name);

    /**
     * An explicit per-knowledge choice always wins; a null content type behaves like {@link #get(String)}.
     */
    default ChunkingStrategy get(String name, String contentType) {
        return get(name);
    }

    String defaultName();

    Set<String> names();
}
