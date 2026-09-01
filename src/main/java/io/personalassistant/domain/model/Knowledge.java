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

    /**
     * Which connector this knowledge uses, plus how it authenticates. Credentials normally live in a
     * reusable {@link Connection} referenced by {@link #connectionId} (null → the default connection
     * for {@link #type}); the inline {@link #auth} blob is a legacy/no-connection fallback the core
     * still tolerates but the connection path supersedes. Neither {@code connectionId} nor {@code auth}
     * is interpreted by the core domain.
     *
     * @param type         the connector family
     * @param connectionId the bound {@link Connection}, or null to resolve the type's default
     * @param auth         inline credentials fallback (empty when a connection is used)
     */
    public record ConnectorDetails(SourceType type, String connectionId, Map<String, Object> auth) {

        public ConnectorDetails {
            auth = auth == null ? Map.of() : auth;
        }

        /** Convenience for the no-connection / inline-auth case (connectionId = null). */
        public static ConnectorDetails of(SourceType type, Map<String, Object> auth) {
            return new ConnectorDetails(type, null, auth);
        }
    }

    /** Operational configuration controlling how the knowledge is kept in sync — and how it is chunked. */
    public record Config(
            ScheduleSettings scheduleSettings,
            WebhookSettings webhookSettings,
            Backfill backfill,
            ChunkingSettings chunking,
            Retention retention) {

        public Config {
            // Older callers/records may omit chunking → treat as "inherit the global default".
            chunking = chunking == null ? ChunkingSettings.inherit() : chunking;
            retention = retention == null ? Retention.inherit() : retention;
        }

        /** Convenience for callers that don't set retention: inherit (which globally means "never expire"). */
        public Config(ScheduleSettings scheduleSettings, WebhookSettings webhookSettings,
                      Backfill backfill, ChunkingSettings chunking) {
            this(scheduleSettings, webhookSettings, backfill, chunking, Retention.inherit());
        }

        /** Convenience for callers that don't set chunking: inherit the global chunking default. */
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

    /**
     * Per-knowledge chunking choice — which {@code ChunkingStrategy} to use and its tunables. Every
     * field is <em>nullable/empty = inherit the global default</em> ({@code app.chunking.*}), so a
     * knowledge only overrides what it cares about. Changing this is a pure in-place config edit:
     * entities indexed afterwards are chunked the new way, while chunks already in the index are left
     * exactly as they were (no re-chunk) — see {@code knowledge-edit-design.md}.
     *
     * @param strategy   strategy name (e.g. {@code "recursive"}, {@code "character"}, {@code "fixed-size"},
     *                   {@code "token"}); null to inherit the global default strategy
     * @param maxSize    target chunk size in the strategy's unit (characters, or tokens for {@code token});
     *                   null to inherit
     * @param overlap    overlap between adjacent chunks, same unit; null to inherit
     * @param separators ordered split separators for {@code recursive}/{@code character}; empty to inherit
     */
    public record ChunkingSettings(String strategy, Integer maxSize, Integer overlap, java.util.List<String> separators) {

        public ChunkingSettings {
            if (strategy != null && strategy.isBlank()) {
                strategy = null;
            }
            separators = separators == null ? java.util.List.of() : java.util.List.copyOf(separators);
        }

        /** All-inherit: no strategy, no sizes, no separators — fall through to {@code app.chunking.*}. */
        public static ChunkingSettings inherit() {
            return new ChunkingSettings(null, null, null, java.util.List.of());
        }
    }

    /**
     * How long this knowledge's entities are kept — the <em>custom</em> tier of retention resolution
     * (custom &rarr; connector {@code defaultRetention()} &rarr; global {@code app.retention.default-period}).
     * Age is measured from {@code Entity.createdAt}; an entity older than the resolved window is
     * tombstoned by {@code RetentionSweeper} and its chunks removed through the ordinary deletion path.
     *
     * <p>Unset at every tier means <strong>never expire</strong>, which is the shipped default. That
     * is deliberate: a document corpus must not silently delete itself, so retention is opt-in and
     * only feed-like sources (job boards, news) turn it on.
     *
     * <p><b>Re-ingest consequence.</b> Tombstoning does not remove the Mongo document, so a source
     * item that still exists is re-created by the next walk's {@code upsert} — re-parsed, re-chunked
     * and re-embedded, with a fresh {@code createdAt}. Windows should therefore be long enough that
     * anything surviving one is genuinely stale. See {@code docs/knowledge-lifecycle.md}.
     *
     * @param period a {@link Durations}-style window such as {@code "14d"}; null/blank to inherit
     */
    public record Retention(String period) {

        public Retention {
            if (period != null && period.isBlank()) {
                period = null;
            }
        }

        /** All-inherit: fall through to the connector default, then the global default. */
        public static Retention inherit() {
            return new Retention(null);
        }

        /** The user's window, or {@code null} when nothing is set here (so the resolver falls through). */
        public Duration custom() {
            return Durations.parse(period);
        }
    }

    /** Rollup counters surfaced for observability. */
    public record Stats(long entities, long indexed, long failed) {
        public static Stats zero() {
            return new Stats(0, 0, 0);
        }
    }
}
