package io.personalassistant.domain.model;

import java.util.Map;

/**
 * What the fenced markIndexed or markFailed writes to {@code enriched} / {@code enrichment}: carried on that
 * write rather than a separate one, so a worker that lost its lease cannot record enrichment for content it no
 * longer owns.
 */
public record EnrichmentOutcome(Kind kind, Map<String, Object> values, Entity.Enrichment stamp) {

    public enum Kind {
        /** No task, nothing stored, or the stored values are current: both fields are left as they are. */
        KEEP,
        /** The knowledge no longer names a task: both fields are removed. */
        CLEAR,
        SET,
        /** Only the stamp is written, carrying the error; the previous values stay. */
        ERROR
    }

    public EnrichmentOutcome {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static EnrichmentOutcome keep() {
        return new EnrichmentOutcome(Kind.KEEP, Map.of(), null);
    }

    public static EnrichmentOutcome clear() {
        return new EnrichmentOutcome(Kind.CLEAR, Map.of(), null);
    }

    public static EnrichmentOutcome set(Map<String, Object> values, Entity.Enrichment stamp) {
        return new EnrichmentOutcome(Kind.SET, values, stamp);
    }

    public static EnrichmentOutcome error(Entity.Enrichment stamp) {
        return new EnrichmentOutcome(Kind.ERROR, Map.of(), stamp);
    }
}
