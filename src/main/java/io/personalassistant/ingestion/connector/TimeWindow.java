package io.personalassistant.ingestion.connector;

import java.time.Instant;

/**
 * A half-open time window {@code [lo, hi)} over an item's event-time, handed to a grabber to seed a
 * walk. Either bound may be {@code null} = unbounded on that side, so the window also encodes the
 * <em>sense</em> of the walk without a separate direction enum:
 *
 * <ul>
 *   <li>{@link #atOrAfter} — {@code [anchor, +inf)} — the forward / incremental sense.</li>
 *   <li>{@link #before}    — {@code (-inf, anchor)} — the backward / backfill sense.</li>
 * </ul>
 *
 * <p>The framework derives the window from a {@link io.personalassistant.domain.model.Cursor}'s
 * direction plus the knowledge anchor and passes it on {@link GrabContext#seedWindow()}; the connector
 * never sees a direction. {@code lo} is inclusive and {@code hi} exclusive, matching the anchor
 * semantics ({@code >= anchor} forward, {@code < anchor} backward).
 *
 * @param lo inclusive lower bound, or null for unbounded-below
 * @param hi exclusive upper bound, or null for unbounded-above
 */
public record TimeWindow(Instant lo, Instant hi) {

    /** {@code [lo, +inf)} — items at or after {@code lo} (the forward / incremental sense). */
    public static TimeWindow atOrAfter(Instant lo) {
        return new TimeWindow(lo, null);
    }

    /** {@code (-inf, hi)} — items strictly before {@code hi} (the backward / backfill sense). */
    public static TimeWindow before(Instant hi) {
        return new TimeWindow(null, hi);
    }

    /** {@code [lo, hi)} — an explicitly bounded range (e.g. a user-narrowed backfill). */
    public static TimeWindow between(Instant lo, Instant hi) {
        return new TimeWindow(lo, hi);
    }

    /** Unbounded on both sides — grab everything (a source with no usable time filter). */
    public static TimeWindow all() {
        return new TimeWindow(null, null);
    }

    /** True when the window has an inclusive lower bound (the mark of a forward walk). */
    public boolean hasLo() {
        return lo != null;
    }

    /** True when the window has an exclusive upper bound (the mark of a backward walk). */
    public boolean hasHi() {
        return hi != null;
    }
}
