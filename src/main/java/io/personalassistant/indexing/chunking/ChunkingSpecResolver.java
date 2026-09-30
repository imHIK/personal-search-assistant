package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Knowledge;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-knowledge settings overlaid on {@code app.chunking.*}; {@code token} defaults to token-scaled sizes.
 * Resolved fresh per entity, so a change applies to the next entity indexed without re-chunking anything.
 */
@ApplicationScoped
public class ChunkingSpecResolver {

    @ConfigProperty(name = "app.chunking.strategy", defaultValue = RecursiveCharacterChunkingStrategy.NAME)
    String defaultStrategy;

    @ConfigProperty(name = "app.chunking.size", defaultValue = "1000")
    int defaultSize;

    @ConfigProperty(name = "app.chunking.overlap", defaultValue = "150")
    int defaultOverlap;

    @ConfigProperty(name = "app.chunking.token.size", defaultValue = "512")
    int defaultTokenSize;

    @ConfigProperty(name = "app.chunking.token.overlap", defaultValue = "64")
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
