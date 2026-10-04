package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.DiscoveryOutcome;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import java.time.Instant;

/**
 * One per (knowledgeId, direction), overwritten by each discovery run while the counters accumulate.
 *
 * @param iterablesFound from the last successful discover; unchanged on failure
 */
public record DiscoveryStatus(
        String id,
        String knowledgeId,
        CursorDirection direction,
        DiscoveryOutcome lastOutcome,
        DiscoveryTrigger lastTrigger,
        Instant lastRunAt,
        int iterablesFound,
        Counts lastCounts,
        long runCount,
        long failureCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public DiscoveryStatus {
        lastCounts = lastCounts == null ? Counts.zero() : lastCounts;
    }

    public record Counts(int created, int revived, int retired) {
        public static Counts zero() {
            return new Counts(0, 0, 0);
        }
    }

    /**
     * A FAILED run's iterablesFound and counts are ignored, so a failure never clobbers the last good values.
     */
    public record Run(
            String knowledgeId,
            CursorDirection direction,
            DiscoveryTrigger trigger,
            DiscoveryOutcome outcome,
            int iterablesFound,
            Counts counts,
            String error,
            Instant ranAt) {

        public Run {
            counts = counts == null ? Counts.zero() : counts;
            ranAt = ranAt == null ? Instant.now() : ranAt;
        }

        public static Run ok(String knowledgeId, CursorDirection direction, DiscoveryTrigger trigger,
                             int iterablesFound, Counts counts) {
            return new Run(knowledgeId, direction, trigger, DiscoveryOutcome.OK,
                    iterablesFound, counts, null, Instant.now());
        }

        public static Run failed(String knowledgeId, CursorDirection direction, DiscoveryTrigger trigger,
                                 String error) {
            return new Run(knowledgeId, direction, trigger, DiscoveryOutcome.FAILED,
                    0, Counts.zero(), error, Instant.now());
        }
    }
}
