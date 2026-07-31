package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Character text splitting — split on a single separator (blank line by default), then merge the
 * fragments up to {@code maxSize} with overlap. Simpler and more predictable than the recursive
 * strategy: it never descends to finer separators, so it preserves whole paragraphs when they fit
 * and is a good fit for text with a consistent delimiter. A single fragment longer than
 * {@code maxSize} is hard-windowed by characters as a safety net so no chunk blows past the target.
 *
 * <p>The separator is {@code spec.separators().get(0)} when provided, else {@code "\n\n"}.
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

        // Safety net: the single-separator merge can leave an over-long fragment (e.g. a paragraph
        // bigger than maxSize with no inner blank line). Hard-window any such piece by characters.
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
