package io.personalassistant.app;

import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.ingestion.job.ForwardCursorScheduler;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Thin orchestration for the manual indexing actions. Triggering a sync re-arms forward cursors
 * (the continuous job then does the work); re-index and delete simply set flags on the entity
 * that the indexing job acts on — keeping these operations cheap and non-blocking.
 */
@ApplicationScoped
public class DefaultIndexingService implements IndexingService {

    private final ForwardCursorScheduler scheduler;
    private final EntityRepository entities;
    private final CursorRepository cursors;

    @Inject
    public DefaultIndexingService(ForwardCursorScheduler scheduler, EntityRepository entities,
                                  CursorRepository cursors) {
        this.scheduler = scheduler;
        this.entities = entities;
        this.cursors = cursors;
    }

    @Override
    public SyncTrigger triggerSync(String knowledgeId) {
        return new SyncTrigger(knowledgeId, scheduler.armNow(knowledgeId));
    }

    @Override
    public void reindexEntity(String entityId) {
        entities.flagNeedsReindex(entityId);
    }

    @Override
    public void deleteEntity(String entityId) {
        entities.markDeleted(entityId, Instant.now());
    }

    @Override
    public RetryTrigger retryFailed(String knowledgeId) {
        // Both halves, because both stages dead-letter independently: a cursor can exhaust its
        // retries fetching while entities fail to index, and a user asking to "retry what failed"
        // means all of it.
        return new RetryTrigger(knowledgeId,
                cursors.retryFailedByKnowledge(knowledgeId),
                entities.retryFailedByKnowledge(knowledgeId));
    }
}
