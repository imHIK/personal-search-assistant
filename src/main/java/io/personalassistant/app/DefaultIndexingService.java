package io.personalassistant.app;

import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.ingestion.job.ForwardCursorScheduler;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Thin orchestration for the manual indexing actions. Triggering a sync re-arms forward cursors
 * (the continuous job then does the work); re-index and delete simply set flags on the entity
 * that the indexing job acts on — keeping these operations cheap and non-blocking.
 */
@ApplicationScoped
public class DefaultIndexingService implements IndexingService {

    private static final Logger LOG = Logger.getLogger(DefaultIndexingService.class.getName());

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
    public int reindexKnowledge(String knowledgeId) {
        int queued = entities.flagNeedsReindexByKnowledge(knowledgeId);
        LOG.info("Queued " + queued + " entities of knowledge " + knowledgeId + " for re-indexing");
        return queued;
    }

    @Override
    public void deleteEntity(String entityId) {
        entities.markDeleted(entityId, Instant.now());
    }

    @Override
    public RetryTrigger retryFailed(String knowledgeId) {
        // Both halves, because both stages dead-letter independently: a cursor can exhaust its
        // retries fetching while entities fail to index, and a user asking to "retry what failed"
        // means all of it. The cursor half also lifts rate-limit holds, which is the console's only
        // way to act on a RATE_LIMITED cursor after raising the account's quota.
        return new RetryTrigger(knowledgeId,
                cursors.retryFailedByKnowledge(knowledgeId),
                entities.retryFailedByKnowledge(knowledgeId));
    }
}
