package io.personalassistant.indexing.chunking;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a strategy's already-split text pieces into {@link Chunk} records. Every strategy produces
 * the same shape of chunk — stable {@code entityId_ordinal} id, denormalized title/uri/sourceType,
 * the entity's facets carried onto each chunk, a rough token estimate, no embedding yet — so that
 * assembly lives here once instead of being copy-pasted into each splitter. Blank pieces are dropped
 * and do not consume an ordinal.
 */
final class ChunkSupport {

    private ChunkSupport() {
    }

    static List<Chunk> toChunks(Entity entity, SourceType sourceType, List<String> pieces) {
        List<Chunk> out = new ArrayList<>(pieces.size());
        // Carry the entity's facets (author, dates, labels, size…) onto every chunk so they're
        // searchable/filterable and returned with each hit. title/uri are also denormalized below.
        Map<String, Object> metadata = entity.metadata() == null ? Map.of() : entity.metadata();
        int ordinal = 0;
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            out.add(new Chunk(
                    Ids.chunk(entity.id(), ordinal),
                    entity.id(),
                    entity.knowledgeId(),
                    entity.iterableId(),
                    sourceType,
                    ordinal,
                    piece,
                    estimateTokens(piece),
                    null,
                    entity.title(),
                    entity.uri(),
                    metadata));
            ordinal++;
        }
        return out;
    }

    /** Rough heuristic used for the stored {@code tokenCount} field: ~4 characters per token. */
    static int estimateTokens(String s) {
        return Math.max(1, s.length() / 4);
    }
}
