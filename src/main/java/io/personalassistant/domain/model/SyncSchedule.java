package io.personalassistant.domain.model;

import java.time.Duration;

/**
 * The cadence at which a knowledge's forward (incremental) cursors are re-armed, expressed as
 * either a fixed {@code interval} <em>or</em> a {@code cron} expression. Both are optional so the
 * same type can represent "no schedule set here" — which is what lets the resolver fall through to
 * the next tier (custom &rarr; connector default &rarr; global default).
 *
 * <p>When both an interval and a cron are present, <strong>cron wins</strong>: it is the more
 * specific instruction (it can pin a wall-clock time, e.g. 2am daily), whereas an interval only
 * says "this often". A tier with neither set is {@link #isPresent() not present}.
 *
 * @param interval fixed gap between re-arms, or {@code null} if a cron (or nothing) is used
 * @param cron     Quartz cron expression (6-field, e.g. {@code "0 0 2 * * ?"}), or {@code null}
 */
public record SyncSchedule(Duration interval, String cron) {

    /** The empty schedule — nothing set here; the resolver falls through to the next tier. */
    public static final SyncSchedule NONE = new SyncSchedule(null, null);

    public SyncSchedule {
        if (cron != null && cron.isBlank()) {
            cron = null;
        }
    }

    public static SyncSchedule ofInterval(Duration interval) {
        return new SyncSchedule(interval, null);
    }

    public static SyncSchedule ofCron(String cron) {
        return new SyncSchedule(null, cron);
    }

    /** True when this tier actually specifies a cadence (interval or cron). */
    public boolean isPresent() {
        return interval != null || cron != null;
    }

    /** True when the cron takes effect (it is set — and therefore preferred over any interval). */
    public boolean usesCron() {
        return cron != null;
    }
}
