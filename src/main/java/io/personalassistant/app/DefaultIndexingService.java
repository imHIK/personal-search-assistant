package io.personalassistant.app;

import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.ingestion.job.ForwardCursorScheduler;
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

    @Inject
    public DefaultIndexingService(ForwardCursorScheduler scheduler, EntityRepository entities) {
        this.scheduler = scheduler;
        this.entities = entities;
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
}
