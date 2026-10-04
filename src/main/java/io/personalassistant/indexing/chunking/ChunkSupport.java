package io.personalassistant.indexing.chunking;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Blank pieces are dropped and do not consume an ordinal. */
final class ChunkSupport {

    private ChunkSupport() {
    }

    static List<Chunk> toChunks(Entity entity, SourceType sourceType, List<String> pieces) {
        return toChunks(entity, sourceType, pieces, List.of());
    }

    /**
     * {@code perChunkFacets} is positional against {@code pieces} and may be shorter; the rest carry the
     * entity's facets only.
     */
    static List<Chunk> toChunks(Entity entity, SourceType sourceType, List<String> pieces,
                                List<Map<String, Object>> perChunkFacets) {
        List<Chunk> out = new ArrayList<>(pieces.size());
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

    private static Map<String, Object> merge(Map<String, Object> entityFacets,
                                             Map<String, Object> chunkFacets) {
        if (chunkFacets.isEmpty()) {
            return entityFacets;
        }
        Map<String, Object> merged = new LinkedHashMap<>(entityFacets);
        merged.putAll(chunkFacets);
        return merged;
    }

    /** About 4 characters per token. */
    static int estimateTokens(String s) {
        return Math.max(1, s.length() / 4);
    }
}
