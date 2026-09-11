package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.enums.EntityStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@code entities} collection (replaces {@code DocumentRepository}).
 * The claim methods power the indexing work queue and, like cursors, must be atomic.
 */
public interface EntityRepository {

    /**
     * Insert or update by natural key {@code (knowledgeId, externalId)}; preserves id/createdAt.
     *
     * <p><b>Field ownership.</b> This writes only the fields ingestion owns — {@code iterableId},
     * {@code entityType}, {@code raw}, {@code content}, {@code metadata}, {@code checksum},
     * {@code expiresAt}, {@code lastSeenGeneration}, {@code updatedAt} — and never the indexer's
     * {@code index.chunkCount}/{@code embeddingModel}/{@code indexedAt}, which describe what is
     * currently in the search index and stay true until the chunks are actually replaced.
     *
     * <p>New content also resets the work queue ({@code status=INGESTED}, {@code needsReindex=false},
     * retry cleared, {@code index.error} cleared) <em>and drops any lease</em>. Dropping the lease is
     * what fences out a worker mid-index on the previous revision: its {@link #markIndexed} is
     * lease-fenced, so it becomes a no-op instead of marking the stale text INDEXED and leaving the
     * new content permanently unsearchable.
     *
     * @return the stored entity as it now exists
     */
    Entity upsert(Entity entity);

    Optional<Entity> findById(String id);

    /** Change-detection lookup during ingestion. */
    Optional<Entity> findByKnowledgeAndExternalId(String knowledgeId, String externalId);

    /**
     * Atomically claim up to {@code limit} entities that need (re)indexing — {@code INGESTED},
     * or {@code needsReindex=true}, or an {@code INDEXING} entity whose lease has expired —
     * flipping each to {@code INDEXING} with a fresh lease.
     */
    List<Entity> claimForIndexing(int limit, String owner, Duration lease);

    /**
     * Same as {@link #claimForIndexing(int, String, Duration)} but restricted to a single
     * knowledge. Used by the indexing job to claim a fair per-knowledge quota so one knowledge's
     * backlog can't starve the others.
     */
    List<Entity> claimForIndexing(String knowledgeId, int limit, String owner, Duration lease);

    /**
     * The distinct knowledge ids that currently have entities awaiting (re)indexing. Drives
     * round-robin fairness in the indexing job. Capped at {@code limit} ids.
     */
    List<String> distinctPendingKnowledgeIds(int limit);

    /** Atomically claim up to {@code limit} tombstoned entities whose chunks still need removal. */
    List<Entity> claimForDeletion(int limit, String owner, Duration lease);

    /**
     * Mark an entity successfully indexed and record what was written, clearing the retry streak.
     *
     * <p>Lease-fenced: applies only if {@code owner} still holds a live lease. Returns {@code false}
     * if the lease was lost, in which case the caller must stop touching this entity — another
     * worker owns it now (invariant 2).
     */
    boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt);

    /**
     * Mark a tombstoned entity's chunks as cleaned (idempotent terminal state). Lease-fenced; see
     * {@link #markIndexed}.
     */
    boolean markDeletionComplete(String id, String owner, Instant cleanedAt);

    /**
     * Record an indexing failure: retry/backoff bookkeeping, or terminal {@code FAILED}. A terminal
     * resting status also clears {@code needsReindex}, so the entity leaves the indexing queue for
     * good — {@link #flagNeedsReindex} is the only way back. Lease-fenced; see {@link #markIndexed}.
     */
    boolean markFailed(String id, String owner, EntityStatus restingStatus, String error,
                       int retryCount, Instant nextAttemptAt);

    /**
     * Dead-letter an entity whose stored content reference no longer resolves, and mark it for
     * re-fetching. One fenced write rather than {@link #markFailed} plus a flag, because markFailed
     * drops the lease and would fence the second call out.
     *
     * <p>Terminal immediately — no retry budget is consumed or granted — because a staged file that
     * has been purged does not come back, so the retry ladder would only postpone an actionable dead
     * letter by {@code retry-limit × backoff}. The {@code needsRefetch} it sets is the recovery
     * route: the next walk that re-lists the item re-materializes it despite an unchanged checksum.
     *
     * @return {@code true} if the caller still held the lease
     */
    boolean markContentMissing(String id, String owner, String error);

    /**
     * Flag every file-backed entity of a knowledge as needing its content fetched again, for
     * connectors whose {@code fileRef} is a staged copy rather than the source file.
     *
     * <p>Pairs with rewinding the knowledge's cursors: the flag alone changes nothing, because an
     * unchanged item never reaches the walk's materialize step, and a rewind alone changes nothing,
     * because the checksum still matches. Together they make the ordinary ingestion walk refresh the
     * content — which keeps re-fetching inside the existing lease, permit and rate-limit machinery
     * instead of issuing one API call per entity from an HTTP thread.
     *
     * <p>Entities with inline text are skipped: their content is in this collection and is not at
     * risk. {@code DELETED} is excluded.
     *
     * @return how many entities were flagged
     */
    int flagNeedsRefetchByKnowledge(String knowledgeId);

    /**
     * Flag an entity for re-indexing without re-fetching (e.g. after a config/model bump), and — if
     * it was dead-lettered — revive it with a fresh retry budget. This is the documented exit from
     * terminal {@code FAILED}. Deliberately leaves any live lease alone: an entity a worker is
     * mid-run on stays out of the queue until that lease lapses.
     */
    void flagNeedsReindex(String id);

    /**
     * Flag every one of a knowledge's entities for re-indexing without re-fetching any of them.
     *
     * <p>The bulk counterpart to {@link #flagNeedsReindex}, and what makes changing the embedding model
     * survivable: vectors from two different models are not comparable even at the same dimension, so a
     * model switch leaves the corpus half-and-half and semantically broken until everything is
     * re-embedded. Entity-at-a-time was the only route before this.
     *
     * <p>Excludes {@code DELETED} (already on its way out) and skips any entity with a live lease, whose
     * in-flight run would otherwise be fenced out mid-write.
     *
     * @return how many entities were flagged
     */
    int flagNeedsReindexByKnowledge(String knowledgeId);

    /**
     * Stamp the generation a walk last saw this entity at — the cheap single-field touch used by the
     * change-detection skip path so an unchanged, already-{@code INDEXED} entity is still recorded as
     * "seen this generation" and doesn't later look stale. Idempotent; leaves {@code updatedAt}.
     */
    void stampLastSeen(String id, long generation);

    /**
     * Return a knowledge's dead-lettered entities to the indexing queue with a fresh retry budget
     * ({@code FAILED} → {@code INGESTED}). The bulk counterpart to {@link #flagNeedsReindex}, and the
     * only other way out of {@code FAILED}.
     *
     * @return how many entities were revived
     */
    int retryFailedByKnowledge(String knowledgeId);

    /** Tombstone an entity so the indexing stage removes its chunks. */
    void markDeleted(String id, Instant updatedAt);

    /**
     * Entities carrying an explicit {@code expiresAt} that has passed — the source told us when the
     * item stops being valid, which always beats the knowledge-level window. Already-{@code DELETED}
     * entities are excluded so a sweep does not re-tombstone what is already on its way out.
     */
    List<Entity> findExpired(int limit, Instant now);

    /**
     * A knowledge's entities created strictly before {@code cutoff} — the knowledge-level retention
     * window, applied only to entities with no explicit {@code expiresAt} of their own.
     *
     * <p>Age is deliberately measured from {@code createdAt} rather than {@code updatedAt}: an item
     * that has sat unchanged is exactly what retention is for, so a change-based clock would never
     * fire on it. Already-{@code DELETED} entities are excluded.
     */
    List<Entity> findCreatedBefore(String knowledgeId, Instant cutoff, int limit);

    List<Entity> findByStatus(EntityStatus status, int limit);

    /**
     * Page a knowledge's entities newest-first for the console's entity browser. Returns
     * {@link EntitySummary} projections rather than full entities — {@code raw} and
     * {@code content.text} dominate an entity document and a listing needs neither.
     *
     * <p>Ordered {@code updatedAt} descending with {@code id} as the tiebreak so paging is
     * deterministic. Note {@link #stampLastSeen} deliberately leaves {@code updatedAt} alone, so a
     * membership re-walk does not reshuffle the listing.
     *
     * @param status optional status filter; {@code null} means all statuses
     */
    List<EntitySummary> findByKnowledge(String knowledgeId, EntityStatus status, int limit, int offset);

    long countByKnowledgeAndStatus(String knowledgeId, EntityStatus status);

    long countByKnowledge(String knowledgeId);

    void delete(String id);

    void deleteByKnowledge(String knowledgeId);

    /** Remove all entities of one iterable within a knowledge (cascade when the iterable is deleted at source). */
    void deleteByKnowledgeAndIterable(String knowledgeId, String iterableId);
}
