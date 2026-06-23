package io.personalassistant.indexing.chunking;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default chunker: fixed-size character windows with overlap. Simple and dependency-free; a
 * sentence/heading-aware strategy can replace it by implementing the same port. Chunk size and
 * overlap are global config so a change is a re-index (flag {@code needsReindex}), not a re-fetch.
 */
@ApplicationScoped
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    @ConfigProperty(name = "app.chunking.size", defaultValue = "1000")
    int size;

    @ConfigProperty(name = "app.chunking.overlap", defaultValue = "150")
    int overlap;

    @Override
    public String name() {
        return "fixed-size";
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text) {
        List<Chunk> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int step = Math.max(1, size - overlap);
        int ordinal = 0;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + size);
            String piece = text.substring(start, end);
            out.add(new Chunk(
                    Ids.chunk(entity.id(), ordinal),
                    entity.id(),
                    entity.knowledgeId(),
                    sourceType,
                    ordinal,
                    piece,
                    estimateTokens(piece),
                    null,
                    entity.title(),
                    entity.uri(),
                    Map.of()));
            ordinal++;
            if (end == text.length()) {
                break;
            }
        }
        return out;
    }

    /** Rough heuristic: ~4 characters per token. */
    private int estimateTokens(String s) {
        return Math.max(1, s.length() / 4);
    }
}
