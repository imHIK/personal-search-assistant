package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Splits on one separator (the spec's first, else a blank line) and merges up to maxSize with overlap, never
 * descending to finer separators.
 */
@ApplicationScoped
public class CharacterChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "character";

    static final String DEFAULT_SEPARATOR = "\n\n";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String separator = spec.separators().isEmpty() ? DEFAULT_SEPARATOR : spec.separators().get(0);
        List<String> fragments = TextSplitters.splitBySeparator(text, separator);
        List<String> merged = TextSplitters.mergeSplits(fragments, separator, spec.maxSize(), spec.overlap());

        // An over-long fragment with no inner separator is hard-windowed by characters.
        List<String> bounded = new java.util.ArrayList<>(merged.size());
        for (String piece : merged) {
            if (piece.length() <= spec.maxSize()) {
                bounded.add(piece);
            } else {
                bounded.addAll(TextSplitters.mergeSplits(
                        TextSplitters.splitBySeparator(piece, ""), "", spec.maxSize(), spec.overlap()));
            }
        }
        return ChunkSupport.toChunks(entity, sourceType, bounded);
    }
}
