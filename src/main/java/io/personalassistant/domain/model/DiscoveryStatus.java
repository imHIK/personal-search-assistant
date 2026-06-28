package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.DiscoveryOutcome;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import java.time.Instant;

/**
 * Observability record for one of a knowledge's <em>grabbers</em>' discovery step. Each knowledge
 * runs up to two grabbers — a {@link CursorDirection#BACKWARD backward} (backfill) grabber and a
 * {@link CursorDirection#FORWARD forward} (incremental) grabber — and {@code connector.discover}
 * enumerates the iterables they walk. There is exactly one document per
 * {@code (knowledgeId, direction)}, overwritten with the latest outcome on each run while the
 * {@code runCount}/{@code failureCount} counters accumulate. Persisted in the Mongo
 * {@code discovery} collection.
 *
 * <p>This fills a visibility gap: previously only an <em>activation-time</em> discovery failure was
 * recorded (as {@code knowledge.status = ERROR}); the recurring reconcile discovery left no trace,
 * so there was no way to check, per grabber, when discovery last ran, what it found, or why it failed.
 *
 * @param id             stable id, {@code dsc_<knowledgeId>:<DIRECTION>}
 * @param knowledgeId    owning knowledge
 * @param direction      which grabber (backward/forward) this status is for
 * @param lastOutcome    outcome of the most recent run
 * @param lastTrigger    what triggered the most recent run (activation vs reconcile)
 * @param lastRunAt      when the most recent run happened
 * @param iterablesFound iterables returned by the last <em>successful</em> discover (unchanged on failure)
 * @param lastCounts     this direction's cursors created/revived/retired by the last successful run
 * @param runCount       total discovery runs (success + failure)
 * @param failureCount   total failed runs
 * @param lastError      compact reason for the last failure, or null when the last run was OK
 * @param createdAt      first time discovery ran for this (knowledge, direction)
 * @param updatedAt      last write time
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

    /** Cursors changed by a discovery run for this direction: created, revived, retired. */
    public record Counts(int created, int revived, int retired) {
        public static Counts zero() {
            return new Counts(0, 0, 0);
        }
    }

    /**
     * One discovery execution for a single grabber direction, as reported by the service. The
     * repository folds this into the stored {@link DiscoveryStatus}, atomically bumping the counters.
     * On a {@code FAILED} run the {@code iterablesFound}/{@code counts} are ignored so a failure never
     * clobbers the last good values.
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

        /** A successful run that found {@code iterablesFound} iterables and changed cursors per {@code counts}. */
        public static Run ok(String knowledgeId, CursorDirection direction, DiscoveryTrigger trigger,
                             int iterablesFound, Counts counts) {
            return new Run(knowledgeId, direction, trigger, DiscoveryOutcome.OK,
                    iterablesFound, counts, null, Instant.now());
        }

        /** A failed run carrying a compact {@code error} summary. */
        public static Run failed(String knowledgeId, CursorDirection direction, DiscoveryTrigger trigger,
                                 String error) {
            return new Run(knowledgeId, direction, trigger, DiscoveryOutcome.FAILED,
                    0, Counts.zero(), error, Instant.now());
        }
    }
}
