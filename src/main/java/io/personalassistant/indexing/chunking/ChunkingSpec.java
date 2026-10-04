package io.personalassistant.indexing.chunking;

import java.util.List;

/**
 * @param maxSize in the strategy's unit: characters, or tokens for {@code token}
 * @param separators empty uses the strategy's own
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

    /** Never zero, so a scan always terminates. */
    public int step() {
        return Math.max(1, maxSize - overlap);
    }
}
