package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.Map;

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
        long lastSeenGeneration) {

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
                createdAt, updatedAt, expiresAt, lastSeenGeneration);
    }

    public Entity withLease(Lease newLease) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, newLease, retry,
                createdAt, updatedAt, expiresAt, lastSeenGeneration);
    }

    /** Leaves updatedAt alone. */
    public Entity withLastSeenGeneration(long generation) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, lease, retry, createdAt,
                updatedAt, expiresAt, generation);
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
