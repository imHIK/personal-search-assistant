package io.personalassistant.testsupport;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.indexing.chunking.ChunkingSpec;
import io.personalassistant.indexing.chunking.ChunkingStrategy;
import java.util.List;
import java.util.Map;

/** Trivial chunker: one chunk holding the whole text (used to keep indexing tests focused). */
public class WholeTextChunkingStrategy implements ChunkingStrategy {

    @Override
    public String name() {
        return "whole-text";
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(new Chunk(Ids.chunk(entity.id(), 0), entity.id(), entity.knowledgeId(),
                entity.iterableId(), sourceType, 0, text, Math.max(1, text.length() / 4), null,
                entity.title(), entity.uri(), Map.of()));
    }
}
