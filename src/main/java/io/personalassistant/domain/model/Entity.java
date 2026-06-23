package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical ingested record: raw source payload + extracted content reference + metadata
 * + lifecycle status. Source of truth in the Mongo {@code entities} collection. Supersedes
 * the older {@code Document}.
 *
 * <p>Mongo is the source of truth and OpenSearch is rebuildable: we retain the full
 * {@link #raw} payload (and, for files, a {@link Content#fileRef}) so re-indexing — with a new
 * chunking config or embedding model — never requires re-fetching from the source.
 *
 * @param id           internal id, e.g. {@code "ent_..."}
 * @param knowledgeId  owning knowledge
 * @param iterableId   sub-stream this entity came from
 * @param entityType   coarse classification (FILE / MESSAGE / …)
 * @param externalId   natural key within the source (path, message id…); unique per knowledge
 * @param raw          complete source response — the controllable re-index source
 * @param content      extracted text and/or a file reference (bytes stay on disk)
 * @param metadata     normalized + source-specific attributes (title, author, uri…)
 * @param checksum     content hash for change detection
 * @param status       indexing lifecycle state
 * @param needsReindex set when content changed or config bumped; forces re-indexing
 * @param index        rollup of what was last indexed for this entity
 * @param lease        indexing lease while {@code status == INDEXING}, else null
 * @param retry        retry bookkeeping for indexing failures
 * @param createdAt    creation timestamp
 * @param updatedAt    last-modified timestamp
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
        IndexInfo index,
        Lease lease,
        Retry retry,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Where the indexable content lives. {@code text} is populated for text entities at
     * ingest; files keep only a {@code fileRef} and are extracted at indexing time.
     *
     * @param text    inline plain text, or null for files
     * @param fileRef absolute local filesystem path ({@code file:///...} or a raw path), or null
     */
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

    /** What was last written to the search index for this entity. */
    public record IndexInfo(int chunkCount, String embeddingModel, Instant indexedAt, String error) {
        public static IndexInfo empty() {
            return new IndexInfo(0, null, null, null);
        }
    }

    /** Indexing lease held by a worker while the entity is {@code INDEXING}. */
    public record Lease(String owner, Instant expiresAt) {
        public boolean isLiveAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now);
        }
    }

    /** Retry bookkeeping for transient indexing failures. */
    public record Retry(int count, Instant nextAttemptAt) {
        public static Retry zero() {
            return new Retry(0, null);
        }

        public Retry increment(Instant nextAttemptAt) {
            return new Retry(count + 1, nextAttemptAt);
        }
    }

    // ---- copy helpers (records are immutable; keep construction centralized) ----------------

    public Entity withStatus(EntityStatus newStatus, Instant updatedAt) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, newStatus, needsReindex, index, lease, retry, createdAt, updatedAt);
    }

    public Entity withLease(Lease newLease) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, index, newLease, retry, createdAt, updatedAt);
    }

    /** Convenience accessor for the display title carried in metadata. */
    public String title() {
        Object t = metadata == null ? null : metadata.get("title");
        return t == null ? null : t.toString();
    }

    /** Convenience accessor for the citation URI carried in metadata. */
    public String uri() {
        Object u = metadata == null ? null : metadata.get("uri");
        return u == null ? null : u.toString();
    }
}
