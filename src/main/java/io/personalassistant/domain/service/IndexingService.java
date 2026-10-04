package io.personalassistant.domain.service;

public interface IndexingService {

    SyncTrigger triggerSync(String knowledgeId);

    /**
     * When the connector's ReindexMode needs a re-fetch, it is synchronous.
     *
     * @throws java.util.NoSuchElementException if no such entity, or its knowledge is gone
     */
    void reindexEntity(String entityId);

    /**
     * The re-fetch flags file-backed entities and rewinds the cursors, so the ordinary walk refreshes them: a
     * whole re-walk of the source is a side effect.
     */
    ReindexTrigger reindexKnowledge(String knowledgeId);

    void deleteEntity(String entityId);

    /**
     * FAILED cursors become AVAILABLE and FAILED entities INGESTED, with fresh retry budgets: the only way
     * out of FAILED. Also releases RATE_LIMITED holds early. Separate from triggerSync, which only arms
     * forward cursors.
     */
    RetryTrigger retryFailed(String knowledgeId);

    /** @param cursorsArmed forward cursors flipped from IDLE to AVAILABLE */
    record SyncTrigger(String knowledgeId, int cursorsArmed) {}

    /**
     * @param refetching how many must be fetched from the source first; 0 when the connector re-indexes from
     *                   stored content
     * @param cursorsReset cursors rewound so the walk re-lists those entities
     */
    record ReindexTrigger(String knowledgeId, int queued, int refetching, int cursorsReset) {}

    record RetryTrigger(String knowledgeId, int cursorsRetried, int entitiesRetried) {}
}
