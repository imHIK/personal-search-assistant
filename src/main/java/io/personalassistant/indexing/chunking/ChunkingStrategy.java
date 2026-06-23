package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;

/**
 * Splits an entity's extracted text into chunks. Pluggable so we can evolve from naive
 * fixed-size+overlap to sentence/heading-aware strategies without touching callers.
 *
 * <p>Text is supplied separately from the entity because, for files, it is extracted at indexing
 * time (the entity only holds a {@code fileRef}). Returned chunks have stable ids derived from
 * {@code entityId + ordinal} and carry denormalized title/uri/sourceType for the search index,
 * but no embedding yet (added downstream).
 */
public interface ChunkingStrategy {

    String name();

    List<Chunk> chunk(Entity entity, SourceType sourceType, String text);
}
