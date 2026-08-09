package io.personalassistant.domain.service;

/**
 * Use-case port for manual indexing operations. The bulk of indexing now runs continuously via
 * the ingestion/indexing jobs (Mongo-polling); this port exposes the on-demand actions a user or
 * operator triggers: kick a forward sync, force a re-index, or remove an item.
 */
public interface IndexingService {

    /** Re-arm a knowledge's forward cursors to pull new/changed items now. */
    SyncTrigger triggerSync(String knowledgeId);

    /** Flag a single entity for re-indexing (e.g. after a chunking/embedding change). No re-fetch. */
    void reindexEntity(String entityId);

    /** Tombstone an entity so the indexing stage removes its chunks from the search index. */
    void deleteEntity(String entityId);

    /**
     * Return a knowledge's dead-lettered work to its queues: {@code FAILED} cursors become
     * {@code AVAILABLE} and {@code FAILED} entities become {@code INGESTED}, both with a fresh retry
     * budget. Nothing else moves either out of {@code FAILED}, so without this a transient burst of
     * failures leaves work permanently stranded.
     *
     * <p>Deliberately separate from {@link #triggerSync}: that one is direction-scoped (forward
     * cursors only) and its {@code cursorsArmed} count is documented as such, while dead-lettered
     * cursors include backward ones. Folding recovery into a routine sync would also remove any way
     * to sync <em>without</em> retrying.
     */
    RetryTrigger retryFailed(String knowledgeId);

    /**
     * @param knowledgeId the knowledge whose forward cursors were re-armed
     * @param cursorsArmed how many forward cursors flipped IDLE → AVAILABLE
     */
    record SyncTrigger(String knowledgeId, int cursorsArmed) {}

    /**
     * @param knowledgeId the knowledge whose dead-lettered work was revived
     * @param cursorsRetried how many cursors flipped FAILED → AVAILABLE
     * @param entitiesRetried how many entities flipped FAILED → INGESTED
     */
    record RetryTrigger(String knowledgeId, int cursorsRetried, int entitiesRetried) {}
}
