package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
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
 * @param status           lifecycle state
 * @param lastError        why the knowledge last failed (set with {@code ERROR}), else null
 * @param stats            rollup counters
 * @param createdAt        creation timestamp
 * @param updatedAt        last-modified timestamp
 */
public record Knowledge(
        String id,
        String name,
        ConnectorDetails connectorDetails,
        Map<String, Object> inputs,
        Config config,
        Instant anchor,
        KnowledgeStatus status,
        String lastError,
        Stats stats,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Return a copy with the given rollup counters. Stats are a <em>derived, reporting-only</em>
     * value computed from entity counts at read time (see {@code KnowledgeService}), not maintained
     * on the write path — so this is used to overlay fresh counts onto a fetched record, leaving
     * {@code updatedAt} untouched (it is not a persisted mutation).
     */
    public Knowledge withStats(Stats newStats) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, status, lastError,
                newStats, createdAt, updatedAt);
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
                    new ScheduleSettings("0 */15 * * * ?", true),
                    new WebhookSettings(false, null),
                    new Backfill(true));
        }
    }

    /** Forward-sync schedule. {@code cron} drives re-arming of forward cursors. */
    public record ScheduleSettings(String cron, boolean enabled) {}

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
