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
 * chunking config or embedding model — usually requires no re-fetch. "Usually", because a
 * {@code fileRef} is only as durable as the file it names: a connector that stages a disposable
 * copy declares {@code FETCH_AND_REINDEX} and its entities are re-fetched instead — see
 * {@link #needsRefetch} and {@code docs/limitations.md} L11.
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
 * @param needsRefetch set when the stored content is a staged copy we no longer trust — because the
 *                     indexer found the file gone, or because a re-index of a
 *                     {@code FETCH_AND_REINDEX} connector asked for fresh bytes. It is the one way
 *                     past the ingestion walk's checksum skip: an item whose source has not changed
 *                     is still re-materialized while this is set. Ingestion-owned, and cleared by
 *                     the same {@code upsert} that writes the new content.
 * @param index        rollup of what was last indexed for this entity
 * @param lease        indexing lease while {@code status == INDEXING}, else null
 * @param retry        retry bookkeeping for indexing failures
 * @param createdAt    creation timestamp
 * @param updatedAt    last-modified timestamp
 * @param expiresAt    when this entity should be aged out, or null to never expire on its own.
 *                     Ingestion-owned: set from the source when the item carries a real end date
 *                     (a posting's close date), and left null otherwise — the knowledge-level
 *                     retention window in {@code RetentionResolver} covers the null case. An
 *                     explicit value always wins over that window. See {@code docs/knowledge-lifecycle.md}.
 * @param lastSeenGeneration the owning knowledge's {@code syncGeneration} the last time a walk saw
 *                     this item. Stamped on every walk — including the change-detection skip path —
 *                     so that after a membership re-walk for generation {@code G}, any entity still
 *                     stamped {@code < G} is one the current rule no longer matches (the Phase 2
 *                     purge signal). See {@code knowledge-edit-design.md}.
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

    /**
     * Retry bookkeeping for transient indexing failures. {@code count} is <em>consecutive</em>
     * failures — a successful index resets it to zero — so the retry limit means "n failures in a
     * row", not "n failures ever".
     */
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
                metadata, checksum, newStatus, needsReindex, needsRefetch, index, lease, retry,
                createdAt, updatedAt, expiresAt, lastSeenGeneration);
    }

    public Entity withLease(Lease newLease) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, newLease, retry,
                createdAt, updatedAt, expiresAt, lastSeenGeneration);
    }

    /** Copy stamped with the generation a walk last saw this entity at (leaves {@code updatedAt}). */
    public Entity withLastSeenGeneration(long generation) {
        return new Entity(id, knowledgeId, iterableId, entityType, externalId, raw, content,
                metadata, checksum, status, needsReindex, needsRefetch, index, lease, retry, createdAt,
                updatedAt, expiresAt, generation);
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
