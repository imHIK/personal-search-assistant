package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive character splitting — the common RAG default. It tries to keep semantic units intact by
 * splitting on a hierarchy of separators (paragraph → line → sentence → word → character) and only
 * descending to a finer separator for fragments that still exceed {@code maxSize}, then merges
 * neighbours back up to the target size with overlap. The result respects paragraph and sentence
 * boundaries far better than a blind fixed window, which usually improves retrieval quality.
 *
 * <p>Separators come from the {@link ChunkingSpec} when set, else the defaults below; the list is
 * always forced to end with {@code ""} so splitting bottoms out at character granularity.
 */
@ApplicationScoped
public class RecursiveCharacterChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "recursive";

    /** Paragraph, line, sentence, clause, word, then character — the usual English-prose ladder. */
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

    /** Ensure the ladder ends with {@code ""} so recursion always terminates at character level. */
    private static List<String> withCharFallback(List<String> separators) {
        if (!separators.isEmpty() && separators.get(separators.size() - 1).isEmpty()) {
            return separators;
        }
        List<String> withFallback = new ArrayList<>(separators);
        withFallback.add("");
        return withFallback;
    }
}
