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
     * @param knowledgeId the knowledge whose forward cursors were re-armed
     * @param cursorsArmed how many forward cursors flipped IDLE → AVAILABLE
     */
    record SyncTrigger(String knowledgeId, int cursorsArmed) {}
}
