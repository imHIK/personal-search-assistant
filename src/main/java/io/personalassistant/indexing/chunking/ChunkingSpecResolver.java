package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Knowledge;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the effective {@link ChunkingSpec} for a knowledge by overlaying its per-knowledge
 * {@link Knowledge.ChunkingSettings} (each field nullable = "inherit") onto the global
 * {@code app.chunking.*} defaults. Character-based strategies default to the character size/overlap;
 * the {@code token} strategy defaults to the (smaller) token size/overlap so a knowledge that just
 * switches to {@code token} gets sensible token-scaled windows without having to also resize.
 *
 * <p>Because {@code IndexingRunner} calls this fresh for every entity it indexes, a settings change
 * takes effect on the next entity indexed with no re-chunk of existing chunks — the "direct update"
 * semantics the edit path guarantees.
 */
@ApplicationScoped
public class ChunkingSpecResolver {

    @ConfigProperty(name = "app.chunking.strategy", defaultValue = RecursiveCharacterChunkingStrategy.NAME)
    String defaultStrategy;

    @ConfigProperty(name = "app.chunking.size", defaultValue = "1000")
    int defaultSize;

    @ConfigProperty(name = "app.chunking.overlap", defaultValue = "150")
    int defaultOverlap;

    @ConfigProperty(name = "app.chunking.token.size", defaultValue = "256")
    int defaultTokenSize;

    @ConfigProperty(name = "app.chunking.token.overlap", defaultValue = "32")
    int defaultTokenOverlap;

    public ChunkingSpec resolve(Knowledge knowledge) {
        Knowledge.ChunkingSettings settings = knowledge == null || knowledge.config() == null
                ? null : knowledge.config().chunking();

        String strategy = settings != null && settings.strategy() != null
                ? settings.strategy() : defaultStrategy;

        boolean token = TokenChunkingStrategy.NAME.equals(strategy);
        int fallbackSize = token ? defaultTokenSize : defaultSize;
        int fallbackOverlap = token ? defaultTokenOverlap : defaultOverlap;

        int size = settings != null && settings.maxSize() != null ? settings.maxSize() : fallbackSize;
        int overlap = settings != null && settings.overlap() != null ? settings.overlap() : fallbackOverlap;
        List<String> separators = settings != null && !settings.separators().isEmpty()
                ? settings.separators() : List.of();

        return new ChunkingSpec(strategy, size, overlap, separators);
    }
}
