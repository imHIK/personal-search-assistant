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
     * {@code lastSeenGeneration}, {@code updatedAt} — and never the indexer's
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
     * Flag an entity for re-indexing without re-fetching (e.g. after a config/model bump), and — if
     * it was dead-lettered — revive it with a fresh retry budget. This is the documented exit from
     * terminal {@code FAILED}. Deliberately leaves any live lease alone: an entity a worker is
     * mid-run on stays out of the queue until that lease lapses.
     */
    void flagNeedsReindex(String id);

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
