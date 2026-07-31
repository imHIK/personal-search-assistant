package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;

/**
 * Splits an entity's extracted text into chunks. Multiple named strategies coexist (fixed-size,
 * character, recursive, token…) and are selected per knowledge through the
 * {@link ChunkingStrategyRegistry}; the {@link ChunkingSpec} carries the per-knowledge tunables
 * (size, overlap, separators) so the same bean serves every knowledge.
 *
 * <p>Text is supplied separately from the entity because, for files, it is extracted at indexing
 * time (the entity only holds a {@code fileRef}). Returned chunks have stable ids derived from
 * {@code entityId + ordinal} and carry denormalized title/uri/sourceType for the search index,
 * but no embedding yet (added downstream).
 */
public interface ChunkingStrategy {

    /** Stable identifier used to select this strategy from the registry (e.g. {@code "recursive"}). */
    String name();

    /** Split {@code text} into ordered chunks according to {@code spec}. */
    List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec);
}
