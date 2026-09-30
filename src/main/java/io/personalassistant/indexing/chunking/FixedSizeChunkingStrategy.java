package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "fixed-size";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        int step = spec.step();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + spec.maxSize());
            pieces.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return ChunkSupport.toChunks(entity, sourceType, pieces);
    }
}
