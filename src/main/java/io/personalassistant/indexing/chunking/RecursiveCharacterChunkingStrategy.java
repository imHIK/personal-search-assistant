package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits on a separator ladder (paragraph, line, sentence, word, character), descending only for fragments
 * still over maxSize, then merges back up with overlap. The ladder always ends with {@code ""} so splitting
 * bottoms out.
 */
@ApplicationScoped
public class RecursiveCharacterChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "recursive";

    static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", ". ", ", ", " ", "");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> separators = withCharFallback(
                spec.separators().isEmpty() ? DEFAULT_SEPARATORS : spec.separators());
        List<String> pieces = TextSplitters.recursiveSplit(text, separators, spec.maxSize(), spec.overlap());
        return ChunkSupport.toChunks(entity, sourceType, pieces);
    }

    private static List<String> withCharFallback(List<String> separators) {
        if (!separators.isEmpty() && separators.get(separators.size() - 1).isEmpty()) {
            return separators;
        }
        List<String> withFallback = new ArrayList<>(separators);
        withFallback.add("");
        return withFallback;
    }
}
