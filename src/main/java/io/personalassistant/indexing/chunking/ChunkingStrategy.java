package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Document;
import java.util.List;

/**
 * Splits a document's text into chunks. Pluggable so we can evolve from naive
 * fixed-size+overlap to sentence/heading-aware strategies without touching callers.
 * Returned chunks have stable ids derived from {@code documentId + ordinal}, but no
 * embedding yet (that is added downstream).
 */
public interface ChunkingStrategy {

    String name();

    List<Chunk> chunk(Document document);
}
