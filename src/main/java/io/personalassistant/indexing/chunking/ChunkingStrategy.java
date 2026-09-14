package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.ParsedContent;
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

    /**
     * Split using the parser's full output, including the structural blocks it reported.
     *
     * <p>A {@code default} so the character-based strategies need no change: they ignore structure and
     * split the flat text exactly as before. A strategy that <em>can</em> use structure overrides this —
     * that is what allows a table to be split on real row boundaries, with its header row repeated into
     * every chunk, rather than by pattern-matching a flattened string.
     */
    default List<Chunk> chunk(Entity entity, SourceType sourceType, ParsedContent parsed,
                              ChunkingSpec spec) {
        return chunk(entity, sourceType, parsed.text(), spec);
    }

    /**
     * Whether this strategy is the better choice for {@code contentType}, consulted by the registry when
     * a knowledge has not asked for a specific strategy (see {@code app.chunking.mime-aware}).
     *
     * <p>Selection was previously keyed only on a per-knowledge name, so every entity in a knowledge got
     * the same splitter regardless of format — a spreadsheet in a knowledge of mostly prose could not be
     * chunked as a table. An explicit per-knowledge choice still wins over this.
     */
    default boolean prefers(String contentType) {
        return false;
    }
}
