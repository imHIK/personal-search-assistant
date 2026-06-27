package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Entity;
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

    /** Insert or update by natural key {@code (knowledgeId, externalId)}; preserves id/createdAt. */
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

    /** Mark an entity successfully indexed and record what was written. */
    void markIndexed(String id, int chunkCount, String embeddingModel, Instant indexedAt);

    /** Mark a tombstoned entity's chunks as cleaned (idempotent terminal state). */
    void markDeletionComplete(String id, Instant cleanedAt);

    /** Record an indexing failure: retry/backoff bookkeeping or terminal {@code FAILED}. */
    void markFailed(String id, EntityStatus restingStatus, String error, int retryCount, Instant nextAttemptAt);

    /** Flag entities for re-indexing without re-fetching (e.g. after a config/model bump). */
    void flagNeedsReindex(String id);

    /** Tombstone an entity so the indexing stage removes its chunks. */
    void markDeleted(String id, Instant updatedAt);

    List<Entity> findByStatus(EntityStatus status, int limit);

    long countByKnowledgeAndStatus(String knowledgeId, EntityStatus status);

    long countByKnowledge(String knowledgeId);

    void delete(String id);

    void deleteByKnowledge(String knowledgeId);

    /** Remove all entities of one iterable within a knowledge (cascade when the iterable is deleted at source). */
    void deleteByKnowledgeAndIterable(String knowledgeId, String iterableId);
}
