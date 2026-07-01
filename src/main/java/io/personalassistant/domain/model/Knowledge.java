package io.personalassistant.domain.model;

import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * A user-added source configuration: connector + inputs + config + lifecycle status.
 * Canonical record in the Mongo {@code knowledge} collection. Supersedes the older
 * {@code Source} concept and adds an {@link #anchor} (the boundary between backward
 * backfill and forward incremental) plus rollup {@link Stats}.
 *
 * @param id               stable id, e.g. {@code "kn_..."}
 * @param name             human-friendly label
 * @param connectorDetails which connector handles this knowledge + opaque auth
 * @param inputs           what to index (paths, folder ids, channels, query…); connector-specific
 * @param config           schedule / webhook / backfill settings
 * @param anchor           boundary between backward ({@code < anchor}) and forward ({@code >= anchor})
 * @param nextSyncDueAt    when the forward scheduler may next re-arm this knowledge's forward
 *                         cursors; {@code null} means "due now" (e.g. fresh activation). Rolled
 *                         forward by the scheduler using the knowledge's resolved {@link SyncSchedule}
 * @param status           lifecycle state
 * @param lastError        why the knowledge last failed (set with {@code ERROR}), else null
 * @param stats            rollup counters
 * @param createdAt        creation timestamp
 * @param updatedAt        last-modified timestamp
 * @param syncGeneration   monotonically increasing counter bumped on every membership-affecting
 *                         edit (see {@code knowledge-edit-design.md}). A walk stamps each entity it
 *                         sees with the current value ({@code Entity.lastSeenGeneration}); an entity
 *                         left behind at a lower generation is, by construction, one that no longer
 *                         matches — the signal the (deferred) Phase 2 purge sweeps on.
 */
public record Knowledge(
        String id,
        String name,
        ConnectorDetails connectorDetails,
        Map<String, Object> inputs,
        Config config,
        Instant anchor,
        Instant nextSyncDueAt,
        KnowledgeStatus status,
        String lastError,
        Stats stats,
        Instant createdAt,
        Instant updatedAt,
        long syncGeneration) {

    /**
     * Return a copy with the given rollup counters. Stats are a <em>derived, reporting-only</em>
     * value computed from entity counts at read time (see {@code KnowledgeService}), not maintained
     * on the write path — so this is used to overlay fresh counts onto a fetched record, leaving
     * {@code updatedAt} untouched (it is not a persisted mutation).
     */
    public Knowledge withStats(Stats newStats) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, status,
                lastError, newStats, createdAt, updatedAt, syncGeneration);
    }

    /** Copy with a new forward re-arm due time (used by the scheduler after it arms/defers). */
    public Knowledge withNextSyncDueAt(Instant newDueAt) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, newDueAt, status,
                lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    /** Copy with a new lifecycle status (used by the edit path to hold/restore status in place). */
    public Knowledge withStatus(KnowledgeStatus newStatus) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, newStatus,
                lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    /**
     * Copy with the edited user-facing fields (name / auth / inputs / schedule-webhook-backfill
     * config) applied and {@code updatedAt} bumped. Everything derived or lifecycle-owned (anchor,
     * status, stats, generation, next-due) is preserved — the edit path adjusts those explicitly.
     */
    public Knowledge withEdits(String newName, ConnectorDetails newConnectorDetails,
                               Map<String, Object> newInputs, Config newConfig, Instant updatedAt) {
        return new Knowledge(id, newName, newConnectorDetails, newInputs, newConfig, anchor,
                nextSyncDueAt, status, lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    /**
     * Copy with the sync generation bumped by one. Called on every membership-affecting edit so a
     * subsequent re-walk stamps freshly-seen entities at the new generation, leaving narrowed-out
     * ones detectably behind.
     */
    public Knowledge bumpGeneration() {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, status,
                lastError, stats, createdAt, updatedAt, syncGeneration + 1);
    }

    /** Which connector + opaque auth blob (never inspected by the core domain). */
    public record ConnectorDetails(SourceType type, Map<String, Object> auth) {}

    /** Operational configuration controlling how the knowledge is kept in sync. */
    public record Config(
            ScheduleSettings scheduleSettings,
            WebhookSettings webhookSettings,
            Backfill backfill) {

        public static Config defaults() {
            return new Config(
                    new ScheduleSettings(null, null, false),
                    new WebhookSettings(false, null),
                    new Backfill(true));
        }
    }

    /**
     * Forward-sync schedule chosen by the user for this specific knowledge — the <em>custom</em>
     * tier of resolution. Either a {@code cron} or an {@code interval} (a {@link Durations}-style
     * string such as {@code "15m"}/{@code "1d"}) may be set; both being unset means "inherit"
     * (fall through to the connector default, then the global default). {@code enabled} is the
     * master switch — when {@code false}, forward cursors are never re-armed on a schedule.
     */
    public record ScheduleSettings(String cron, String interval, boolean enabled) {

        public ScheduleSettings {
            if (cron != null && cron.isBlank()) {
                cron = null;
            }
            if (interval != null && interval.isBlank()) {
                interval = null;
            }
        }

        /**
         * The user's custom schedule as a {@link SyncSchedule}, or {@link SyncSchedule#NONE} when
         * none is set (so the resolver moves to the next tier). Cron is preferred over interval.
         */
        public SyncSchedule customSchedule() {
            if (cron != null) {
                return SyncSchedule.ofCron(cron);
            }
            Duration parsed = Durations.parse(interval);
            return parsed == null ? SyncSchedule.NONE : SyncSchedule.ofInterval(parsed);
        }
    }

    /** Inbound webhook configuration (forward re-arm on demand). */
    public record WebhookSettings(boolean enabled, String secret) {}

    /** Whether to walk history backward from the anchor on first activation. */
    public record Backfill(boolean enabled) {}

    /** Rollup counters surfaced for observability. */
    public record Stats(long entities, long indexed, long failed) {
        public static Stats zero() {
            return new Stats(0, 0, 0);
        }
    }
}
