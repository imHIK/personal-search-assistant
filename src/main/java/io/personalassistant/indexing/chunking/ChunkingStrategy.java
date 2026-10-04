package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.ParsedContent;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;

/** Text is passed separately because a file is extracted at indexing time. */
public interface ChunkingStrategy {

    String name();

    List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec);

    /** A strategy that can use structure overrides this; the default splits the flat text. */
    default List<Chunk> chunk(Entity entity, SourceType sourceType, ParsedContent parsed,
                              ChunkingSpec spec) {
        return chunk(entity, sourceType, parsed.text(), spec);
    }

    /** Consulted only when the knowledge did not ask for a specific strategy. */
    default boolean prefers(String contentType) {
        return false;
    }
}
