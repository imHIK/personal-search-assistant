package io.personalassistant.indexing.chunking;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return toChunks(entity, sourceType, pieces, List.of());
    }

    /**
     * As above, but with per-chunk facets — where in the document each piece came from (sheet, page,
     * heading path, row range). {@code perChunkFacets} is positional against {@code pieces} and may be
     * empty or shorter, in which case the remaining chunks just carry the entity's facets.
     *
     * <p>These are what let a hit say <em>"rows 17–31 of the holidays table"</em> rather than only naming
     * the document, and what the answer prompt uses to locate a source. Chunk-level facets were promised
     * by this class's contract from the start but no strategy ever supplied any.
     */
    static List<Chunk> toChunks(Entity entity, SourceType sourceType, List<String> pieces,
                                List<Map<String, Object>> perChunkFacets) {
        List<Chunk> out = new ArrayList<>(pieces.size());
        // Carry the entity's facets (author, dates, labels, size…) onto every chunk so they're
        // searchable/filterable and returned with each hit. title/uri are also denormalized below.
        Map<String, Object> entityFacets = entity.metadata() == null ? Map.of() : entity.metadata();
        int ordinal = 0;
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
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
                    merge(entityFacets, i < perChunkFacets.size() ? perChunkFacets.get(i) : Map.of())));
            ordinal++;
        }
        return out;
    }

    /**
     * Entity facets plus this chunk's own. A fresh map per chunk only when there is something
     * chunk-specific to add — otherwise every chunk of an entity keeps sharing one immutable map, as
     * before, rather than paying for a copy each.
     */
    private static Map<String, Object> merge(Map<String, Object> entityFacets,
                                             Map<String, Object> chunkFacets) {
        if (chunkFacets.isEmpty()) {
            return entityFacets;
        }
        Map<String, Object> merged = new LinkedHashMap<>(entityFacets);
        merged.putAll(chunkFacets);
        return merged;
    }

    /** Rough heuristic used for the stored {@code tokenCount} field: ~4 characters per token. */
    static int estimateTokens(String s) {
        return Math.max(1, s.length() / 4);
    }
}
