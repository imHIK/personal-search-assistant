package io.personalassistant.indexing.chunking;

import java.util.List;

/**
 * The tunables a {@link ChunkingStrategy} needs for one run, resolved per knowledge (custom settings
 * overlaid on the global defaults — see {@link ChunkingSpecResolver}). Passing this in, rather than
 * reading global config inside each strategy, is what lets two knowledges chunk differently at the
 * same time and lets a knowledge's chunking be changed without touching the strategy beans.
 *
 * <p>{@code maxSize}/{@code overlap} are in the strategy's natural unit: <em>characters</em> for the
 * character-based strategies ({@code recursive}, {@code character}, {@code fixed-size}) and
 * <em>tokens</em> for {@code token}. {@code separators} is the ordered split hierarchy used by the
 * {@code recursive}/{@code character} strategies; empty means "use the strategy's own defaults".
 *
 * @param strategy    the strategy name to select from the registry (e.g. {@code "recursive"})
 * @param maxSize     target maximum chunk size in the strategy's unit ({@code >= 1})
 * @param overlap     overlap between adjacent chunks, same unit ({@code 0 <= overlap < maxSize})
 * @param separators  ordered separators for hierarchical/character splitting; empty = strategy default
 */
public record ChunkingSpec(String strategy, int maxSize, int overlap, List<String> separators) {

    public ChunkingSpec {
        if (strategy == null || strategy.isBlank()) {
            strategy = "recursive";
        }
        if (maxSize < 1) {
            maxSize = 1;
        }
        if (overlap < 0) {
            overlap = 0;
        }
        // Overlap must stay below the window so the walk always makes forward progress.
        if (overlap >= maxSize) {
            overlap = maxSize - 1;
        }
        separators = separators == null ? List.of() : List.copyOf(separators);
    }

    /** Advance between successive windows (never zero, so a scan always terminates). */
    public int step() {
        return Math.max(1, maxSize - overlap);
    }
}
