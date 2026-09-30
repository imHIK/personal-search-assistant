package io.personalassistant.domain.model;

import java.time.Duration;

/**
 * A forward cadence: an interval or a cron, the cron winning when both are set. Neither set means this tier
 * has none, and the resolver falls through (custom, connector, global).
 *
 * @param cron evaluated in UTC; 5-field Unix or 6/7-field Quartz
 */
public record SyncSchedule(Duration interval, String cron) {

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

    public boolean isPresent() {
        return interval != null || cron != null;
    }

    public boolean usesCron() {
        return cron != null;
    }
}
