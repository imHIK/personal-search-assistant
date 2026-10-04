package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical ingested record in Mongo; OpenSearch is rebuildable from it.
 *
 * @param externalId natural key within the source; unique per knowledge
 * @param checksum the only change signal: unchanged means a walk skips the item
 * @param needsRefetch the stored content is a staged copy no longer trusted, the one way past the checksum
 *                     skip. Ingestion-owned; cleared by the upsert that writes new content
 * @param lease the indexing lease while INDEXING
 * @param expiresAt when to age out, set only when the source states an end date; wins over the retention
 *                  window
 * @param lastSeenGeneration the knowledge's syncGeneration when a walk last saw it; lower after a membership
 *                           re-walk means the rule no longer matches
 * @param enriched fields a metadata task produced. Indexer-owned and kept apart from {@code metadata}, which
 *                 upsert replaces wholesale
 * @param enrichment what produced {@code enriched}, so a pass over unchanged content skips the LLM call
 * @param custom user marks written through the API; neither ingestion nor the indexer touches them
 */
public record Entity(
        String id,
        String knowledgeId,
        String iterableId,
        EntityType entityType,
        String externalId,
        Map<String, Object> raw,
        Content content,
        Map<String, Object> metadata,
        String checksum,
        EntityStatus status,
        boolean needsReindex,
        boolean needsRefetch,
        IndexInfo index,
        Lease lease,
        Retry retry,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long lastSeenGeneration,
        Map<String, Object> enriched,
        Enrichment enrichment,
        Map<String, Object> custom) {

    public Entity {
        enriched = enriched == null ? Map.of() : enriched;
        custom = custom == null ? Map.of() : custom;
    }

    public Entity(String id, String knowledgeId, String iterableId, EntityType entityType, String externalId,
                  Map<String, Object> raw, Content content, Map<String, Object> metadata, String checksum,
                  EntityStatus status, boolean needsReindex, boolean needsRefetch, IndexInfo index, Lease lease,
                  Retry retry, Instant createdAt, Instant updatedAt, Instant expiresAt,
                  long lastSeenGeneration) {
        this(id, knowledgeId, iterableId, entityType, externalId, raw, content, metadata, checksum, status,
                needsReindex, needsRefetch, index, lease, retry, createdAt, updatedAt, expiresAt,
                lastSeenGeneration, Map.of(), null, Map.of());
    }

    /**
     * @param taskVersion the task's updatedAt when it ran: an edited task makes the values stale
     * @param checksum the entity's checksum when it ran: new content makes them stale
     * @param error the last attempt failed; {@code enriched} still holds the previous values, if any
     */
    public record Enrichment(String taskId, Instant taskVersion, String checksum, Instant at, String error) {

        public boolean isCurrentFor(String currentTaskId, Instant currentTaskVersion, String currentChecksum) {
            return error == null
                    && Objects.equals(taskId, currentTaskId)
                    && Objects.equals(taskVersion, currentTaskVersion)
                    && Objects.equals(checksum, currentChecksum);
        }
    }

    /** Inline text, or a fileRef extracted at indexing time; bytes never live in Mongo. */
    public record Content(String text, String fileRef) {
        public static Content ofText(String text) {
            return new Content(text, null);
        }

        public static Content ofFile(String fileRef) {
            return new Content(null, fileRef);
        }

        public boolean isFile() {
            return fileRef != null && !fileRef.isBlank();
        }
    }

    public record IndexInfo(int chunkCount, String embeddingModel, Instant indexedAt, String error) {
        public static IndexInfo empty() {
            return new IndexInfo(0, null, null, null);
        }
    }

    public record Lease(String owner, Instant expiresAt) {
        public boolean isLiveAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }

    /** {@code count} is consecutive failures; a successful index resets it. */
    public record Retry(int count, Instant nextAttemptAt) {
        public static Retry zero() {
            return new Retry(0, null);
        }

        public Retry increment(Instant nextAttemptAt) {
            return new Retry(count + 1, nextAttemptAt);
        }
    }

    public Entity withStatus(EntityStatus newStatus, Instant updatedAt) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, newStatus, needsReindex, needsRefetch, index, lease, retry,
                createdAt, updatedAt, expiresAt, lastSeenGeneration, enriched, enrichment, custom);
    }

    public Entity withLease(Lease newLease) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, newLease, retry,
                createdAt, updatedAt, expiresAt, lastSeenGeneration, enriched, enrichment, custom);
    }

    /** Leaves updatedAt alone. */
    public Entity withLastSeenGeneration(long generation) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, lease, retry, createdAt,
                updatedAt, expiresAt, generation, enriched, enrichment, custom);
    }

    /** For chunking: the enriched values are merged in, and a non-null connector value wins a clash. */
    public Entity withMetadata(Map<String, Object> newMetadata) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                newMetadata, checksum, status, needsReindex, needsRefetch, index, lease, retry, createdAt,
                updatedAt, expiresAt, lastSeenGeneration, enriched, enrichment, custom);
    }

    public static Map<String, Object> mergeEnriched(Map<String, Object> metadata, Map<String, Object> values) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (values != null) {
            values.forEach((key, value) -> {
                if (merged.get(key) == null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    public String title() {
        Object t = metadata == null ? null : metadata.get("title");
        return t == null ? null : t.toString();
    }

    public String uri() {
        Object u = metadata == null ? null : metadata.get("uri");
        return u == null ? null : u.toString();
    }
}
