package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityQuery;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.enums.EntityStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The claim methods power the indexing queue and must be atomic. */
public interface EntityRepository {

    /**
     * Writes only the fields ingestion owns, never the indexer's {@code index.*}, which stay true until the
     * chunks are replaced. New content resets the work queue and drops any lease, fencing out a worker
     * mid-index on the previous revision: its markIndexed becomes a no-op.
     *
     * @return the stored entity
     */
    Entity upsert(Entity entity);

    Optional<Entity> findById(String id);

    Optional<Entity> findByKnowledgeAndExternalId(String knowledgeId, String externalId);

    /** INGESTED, needsReindex, or INDEXING with an expired lease, flipped to INDEXING with a fresh lease. */
    List<Entity> claimForIndexing(int limit, String owner, Duration lease);

    /** Restricted to one knowledge, for the indexing job's fair per-knowledge quota. */
    List<Entity> claimForIndexing(String knowledgeId, int limit, String owner, Duration lease);

    List<String> distinctPendingKnowledgeIds(int limit);

    /** Tombstoned entities whose chunks still need removal. */
    List<Entity> claimForDeletion(int limit, String owner, Duration lease);

    /**
     * Clears the retry streak. Fenced: false means the lease was lost, and the caller must stop touching the
     * entity.
     */
    boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt);

    /** An idempotent terminal state. Fenced. */
    boolean markDeletionComplete(String id, String owner, Instant cleanedAt);

    /** A terminal status also clears needsReindex; flagNeedsReindex is the only way back. Fenced. */
    boolean markFailed(String id, String owner, EntityStatus restingStatus, String error,
                       int retryCount, Instant nextAttemptAt);

    /**
     * Dead-letters and sets needsRefetch in one fenced write, since markFailed drops the lease and would
     * fence out a second call. Terminal at once: a purged staged file does not come back, and needsRefetch is
     * the recovery route.
     *
     * @return false if the lease was lost
     */
    boolean markContentMissing(String id, String owner, String error);

    /**
     * Pairs with rewinding the cursors: the flag alone never reaches an unchanged item, and a rewind alone
     * meets a matching checksum. Inline-text and DELETED entities are skipped.
     *
     * @return how many were flagged
     */
    int flagNeedsRefetchByKnowledge(String knowledgeId);

    /** Also revives a FAILED entity: the documented exit. A live lease is left alone. */
    void flagNeedsReindex(String id);

    /**
     * For an embedding-model change, when every vector must be rebuilt. Excludes DELETED and skips entities
     * with a live lease.
     *
     * @return how many were flagged
     */
    int flagNeedsReindexByKnowledge(String knowledgeId);

    /** Leaves updatedAt alone, so a re-walk does not reshuffle the listing. */
    void stampLastSeen(String id, long generation);

    /** FAILED to INGESTED with a fresh retry budget. */
    int retryFailedByKnowledge(String knowledgeId);

    void markDeleted(String id, Instant updatedAt);

    /** Excludes DELETED, so a sweep does not re-tombstone. */
    List<Entity> findExpired(int limit, Instant now);

    /** Only entities with no expiresAt of their own; excludes DELETED. */
    List<Entity> findCreatedBefore(String knowledgeId, Instant cutoff, int limit);

    List<Entity> findByStatus(EntityStatus status, int limit);

    /**
     * Summaries, since raw and content.text dominate a document. Ordered by updatedAt descending with id as
     * the tiebreak, so paging is deterministic.
     */
    List<EntitySummary> findByKnowledge(String knowledgeId, EntityQuery query, int limit, int offset);

    /** Must apply exactly findByKnowledge's filter, or the last page renders empty. */
    long countByKnowledge(String knowledgeId, EntityQuery query);

    long countByKnowledgeAndStatus(String knowledgeId, EntityStatus status);

    long countByKnowledge(String knowledgeId);

    void delete(String id);

    void deleteByKnowledge(String knowledgeId);

    void deleteByKnowledgeAndIterable(String knowledgeId, String iterableId);
}
