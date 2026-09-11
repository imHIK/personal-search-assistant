package io.personalassistant.domain.service;

/**
 * Use-case port for manual indexing operations. The bulk of indexing now runs continuously via
 * the ingestion/indexing jobs (Mongo-polling); this port exposes the on-demand actions a user or
 * operator triggers: kick a forward sync, force a re-index, or remove an item.
 */
public interface IndexingService {

    /** Re-arm a knowledge's forward cursors to pull new/changed items now. */
    SyncTrigger triggerSync(String knowledgeId);

    /**
     * Make one entity current again: re-index it, first re-fetching its content when the connector's
     * {@code ReindexMode} says the stored reference cannot be trusted (Drive's staged copy).
     *
     * <p>The fetch, when it happens, is synchronous — one user-chosen item, and the caller wants to
     * know it worked. It is also the only way to reach a single item: a walk lists pages, so
     * re-covering one known entity through the cursors would mean re-walking everything ahead of it.
     *
     * @throws java.util.NoSuchElementException if no such entity, or its knowledge is gone
     */
    void reindexEntity(String entityId);

    /**
     * Re-index every entity of a knowledge, re-fetching content first for connectors whose stored
     * reference is a staged copy.
     *
     * <p>The reason this exists is changing the embedding model: same dimension does not mean
     * comparable vectors, so a switch silently degrades search until the whole corpus is re-embedded.
     * That is also what made re-fetching non-optional here — the first model change after a Drive
     * knowledge had been sitting for a few days queued every staged file at once, and every one of
     * them had been purged from the temp dir (L11).
     *
     * <p>The re-fetch half does not download anything itself: it flags the file-backed entities and
     * rewinds the cursors, so the ordinary ingestion walk refreshes them inside the lease, permit and
     * rate-limit machinery it already has. That makes the call cheap and the work incremental, but it
     * also means a whole re-walk of the source is a visible side effect.
     */
    ReindexTrigger reindexKnowledge(String knowledgeId);

    /** Tombstone an entity so the indexing stage removes its chunks from the search index. */
    void deleteEntity(String entityId);

    /**
     * Return a knowledge's dead-lettered work to its queues: {@code FAILED} cursors become
     * {@code AVAILABLE} and {@code FAILED} entities become {@code INGESTED}, both with a fresh retry
     * budget. Nothing else moves either out of {@code FAILED}, so without this a transient burst of
     * failures leaves work permanently stranded.
     *
     * <p>It also releases {@code RATE_LIMITED} cursors early, clearing the hold the limiter wrote.
     * That instant was computed against a quota the caller has typically just changed, so this
     * doubles as "I have raised the limit, run now" — the only way to shorten a hold.
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
     * @param knowledgeId the knowledge whose entities were queued
     * @param queued how many entities were flagged for re-indexing
     * @param refetching how many of those must be fetched from the source first (0 when the
     *                   connector re-indexes from stored content)
     * @param cursorsReset how many cursors were rewound so the walk re-lists those entities
     */
    record ReindexTrigger(String knowledgeId, int queued, int refetching, int cursorsReset) {}

    /**
     * @param knowledgeId the knowledge whose dead-lettered work was revived
     * @param cursorsRetried how many cursors flipped FAILED → AVAILABLE
     * @param entitiesRetried how many entities flipped FAILED → INGESTED
     */
    record RetryTrigger(String knowledgeId, int cursorsRetried, int entitiesRetried) {}
}
