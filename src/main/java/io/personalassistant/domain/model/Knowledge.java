package io.personalassistant.domain.model;

import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * @param anchor set at creation and never moved: backward grabs take items {@code < anchor}, forward ones
 *               {@code >= anchor}
 * @param nextSyncDueAt when forward cursors may next be re-armed; null means due now
 * @param syncGeneration bumped on every membership-affecting edit; entities stamped lower afterwards no
 *                       longer match
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

    /** Stats are derived at read time; this overlays them without touching updatedAt. */
    public Knowledge withStats(Stats newStats) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, status,
                lastError, newStats, createdAt, updatedAt, syncGeneration);
    }

    public Knowledge withNextSyncDueAt(Instant newDueAt) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, newDueAt, status,
                lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    public Knowledge withStatus(KnowledgeStatus newStatus) {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, newStatus,
                lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    /**
     * Anchor, status, stats, generation and next-due are preserved; the edit path adjusts them explicitly.
     */
    public Knowledge withEdits(String newName, ConnectorDetails newConnectorDetails,
                               Map<String, Object> newInputs, Config newConfig, Instant updatedAt) {
        return new Knowledge(id, newName, newConnectorDetails, newInputs, newConfig, anchor,
                nextSyncDueAt, status, lastError, stats, createdAt, updatedAt, syncGeneration);
    }

    public Knowledge bumpGeneration() {
        return new Knowledge(id, name, connectorDetails, inputs, config, anchor, nextSyncDueAt, status,
                lastError, stats, createdAt, updatedAt, syncGeneration + 1);
    }

    /**
     * @param connectionId null resolves the type's default connection
     * @param auth inline credentials; unused when a connection is
     */
    public record ConnectorDetails(SourceType type, String connectionId, Map<String, Object> auth) {

        public ConnectorDetails {
            auth = auth == null ? Map.of() : auth;
        }

        public static ConnectorDetails of(SourceType type, Map<String, Object> auth) {
            return new ConnectorDetails(type, null, auth);
        }
    }

    public record Config(
            ScheduleSettings scheduleSettings,
            WebhookSettings webhookSettings,
            Backfill backfill,
            ChunkingSettings chunking,
            Retention retention) {

        public Config {
            chunking = chunking == null ? ChunkingSettings.inherit() : chunking;
            retention = retention == null ? Retention.inherit() : retention;
        }

        public Config(ScheduleSettings scheduleSettings, WebhookSettings webhookSettings,
                      Backfill backfill, ChunkingSettings chunking) {
            this(scheduleSettings, webhookSettings, backfill, chunking, Retention.inherit());
        }

        public Config(ScheduleSettings scheduleSettings, WebhookSettings webhookSettings, Backfill backfill) {
            this(scheduleSettings, webhookSettings, backfill, ChunkingSettings.inherit(), Retention.inherit());
        }

        public static Config defaults() {
            return new Config(
                    new ScheduleSettings(null, null, false),
                    new WebhookSettings(false, null),
                    new Backfill(true),
                    ChunkingSettings.inherit(),
                    Retention.inherit());
        }
    }

    /**
     * Both unset inherits: the connector default, then the global one. With {@code enabled} false, forward
     * cursors are never re-armed on a schedule.
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
         * {@link SyncSchedule#NONE} when nothing is set, so the resolver moves to the next tier. Cron wins
         * over interval.
         */
        public SyncSchedule customSchedule() {
            if (cron != null) {
                return SyncSchedule.ofCron(cron);
            }
            Duration parsed = Durations.parse(interval);
            return parsed == null ? SyncSchedule.NONE : SyncSchedule.ofInterval(parsed);
        }
    }

    public record WebhookSettings(boolean enabled, String secret) {}

    /** Whether to walk history backward from the anchor on first activation. */
    public record Backfill(boolean enabled) {}

    /**
     * Every null or empty field inherits {@code app.chunking.*}. Changing it re-chunks nothing: only entities
     * indexed afterwards use it.
     */
    public record ChunkingSettings(String strategy, Integer maxSize, Integer overlap, java.util.List<String> separators) {

        public ChunkingSettings {
            if (strategy != null && strategy.isBlank()) {
                strategy = null;
            }
            separators = separators == null ? java.util.List.of() : java.util.List.copyOf(separators);
        }

        public static ChunkingSettings inherit() {
            return new ChunkingSettings(null, null, null, java.util.List.of());
        }
    }

    /**
     * Custom, then the connector's {@code defaultRetention()}, then {@code app.retention.default-period};
     * unset everywhere means never expire. Age runs from {@code Entity.createdAt}. A tombstoned item still at
     * the source is re-created by the next walk, so a window should be long enough that anything surviving it
     * is stale.
     */
    public record Retention(String period) {

        public Retention {
            if (period != null && period.isBlank()) {
                period = null;
            }
        }

        public static Retention inherit() {
            return new Retention(null);
        }

        public Duration custom() {
            return Durations.parse(period);
        }
    }

    public record Stats(long entities, long indexed, long failed) {
        public static Stats zero() {
            return new Stats(0, 0, 0);
        }
    }
}
