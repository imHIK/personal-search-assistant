package io.personalassistant.ingestion.connector;

import java.time.Instant;

/**
 * Half-open {@code [lo, hi)}; a null bound is unbounded. Its shape encodes the walk's sense: {@code [anchor,
 * +inf)} forward, {@code (-inf, anchor)} backfill.
 */
public record TimeWindow(Instant lo, Instant hi) {

    public static TimeWindow atOrAfter(Instant lo) {
        return new TimeWindow(lo, null);
    }

    public static TimeWindow before(Instant hi) {
        return new TimeWindow(null, hi);
    }

    public static TimeWindow between(Instant lo, Instant hi) {
        return new TimeWindow(lo, hi);
    }

    public static TimeWindow all() {
        return new TimeWindow(null, null);
    }

    public boolean hasLo() {
        return lo != null;
    }

    public boolean hasHi() {
        return hi != null;
    }
}
