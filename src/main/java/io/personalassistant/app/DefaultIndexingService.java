package io.personalassistant.app;

import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.job.ForwardCursorScheduler;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Re-index refetches one entity here and now, since a walk cannot be asked for a single id, but a whole
 * knowledge by flagging it and rewinding its cursors, so the walk's leases, permits and rate limits apply.
 */
@ApplicationScoped
public class DefaultIndexingService implements IndexingService {

    private static final Logger LOG = Logger.getLogger(DefaultIndexingService.class.getName());

    private final ForwardCursorScheduler scheduler;
    private final EntityRepository entities;
    private final CursorRepository cursors;
    private final KnowledgeRepository knowledge;
    private final ConnectorRegistry connectors;
    private final RefetchPolicy refetchPolicy;

    @Inject
    public DefaultIndexingService(ForwardCursorScheduler scheduler, EntityRepository entities,
                                  CursorRepository cursors, KnowledgeRepository knowledge,
                                  ConnectorRegistry connectors, RefetchPolicy refetchPolicy) {
        this.scheduler = scheduler;
        this.entities = entities;
        this.cursors = cursors;
        this.knowledge = knowledge;
        this.connectors = connectors;
        this.refetchPolicy = refetchPolicy;
    }

    @Override
    public SyncTrigger triggerSync(String knowledgeId) {
        return new SyncTrigger(knowledgeId, scheduler.armNow(knowledgeId));
    }

    @Override
    public void reindexEntity(String entityId) {
        Entity entity = entities.findById(entityId)
                .orElseThrow(() -> new NoSuchElementException("No entity with id " + entityId));
        Knowledge kn = knowledge.findById(entity.knowledgeId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Owning knowledge " + entity.knowledgeId() + " not found"));

        if (!refetchPolicy.refetches(kn, entity)) {
            entities.flagNeedsReindex(entityId);
            return;
        }
        refetch(kn, entity);
    }

    /**
     * The upsert does the queue transition (INGESTED, flags cleared, lease dropped). Storing the item exactly
     * as grab would is what keeps the next walk from seeing a source-side change.
     */
    private void refetch(Knowledge kn, Entity entity) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());
        Optional<RawItem> fetched;
        try {
            fetched = connector.fetchOne(kn, entity);
        } catch (RateLimitedException e) {
            // A conflict rather than a 500: the caller can wait, or raise the quota and retry.
            throw new IllegalStateException("Rate limited by " + e.key()
                    + "; re-index of entity " + entity.id() + " can be retried after " + e.retryAt(), e);
        }
        if (fetched.isEmpty() || fetched.get().deleted()) {
            LOG.info("Entity " + entity.id() + " no longer exists at the source; tombstoning");
            entities.markDeleted(entity.id(), Instant.now());
            return;
        }
        RawItem item = fetched.get();
        Entity.Content content = connector.materialize(kn, item);
        entities.upsert(new Entity(entity.id(), kn.id(), entity.iterableId(), item.entityType(),
                item.externalId(), item.raw(), content, item.metadata(), item.checksum(),
                EntityStatus.INGESTED, false, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), entity.createdAt(), Instant.now(), item.expiresAt(),
                entity.lastSeenGeneration()));
        LOG.info("Re-fetched entity " + entity.id() + " from " + kn.connectorDetails().type()
                + " and queued it for re-indexing");
    }

    @Override
    public ReindexTrigger reindexKnowledge(String knowledgeId) {
        Knowledge kn = knowledge.findById(knowledgeId)
                .orElseThrow(() -> new NoSuchElementException("No knowledge with id " + knowledgeId));

        int queued = entities.flagNeedsReindexByKnowledge(knowledgeId);
        int refetching = 0;
        int cursorsReset = 0;
        if (refetchPolicy.refetches(kn)) {
            // Flag before rewinding, never after: a cursor re-armed first could already be walking pages, and
            // its items would take the unchanged-checksum skip with their stale content.
            refetching = entities.flagNeedsRefetchByKnowledge(knowledgeId);
            cursorsReset = rewindForRefetch(kn);
        }
        LOG.info("Queued " + queued + " entities of knowledge " + knowledgeId + " for re-indexing"
                + (refetching > 0 ? " (" + refetching + " to be re-fetched first, across "
                + cursorsReset + " rewound cursor(s))" : ""));
        return new ReindexTrigger(knowledgeId, queued, refetching, cursorsReset);
    }

    /**
     * Leaves RETIRED cursors alone, and rewinds a BACKWARD one only while backfill is enabled or once it has
     * drained, so a backfill the user turned off does not restart.
     */
    private int rewindForRefetch(Knowledge kn) {
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        int reset = 0;
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            if (c.status() == CursorStatus.RETIRED) {
                continue;
            }
            if (c.direction() == CursorDirection.BACKWARD
                    && !backfill && c.status() != CursorStatus.EXHAUSTED) {
                continue;
            }
            if (cursors.resetToStart(c.id())) {
                reset++;
            }
        }
        return reset;
    }

    @Override
    public void deleteEntity(String entityId) {
        entities.markDeleted(entityId, Instant.now());
    }

    @Override
    public RetryTrigger retryFailed(String knowledgeId) {
        // Both stages dead-letter independently. The cursor half also lifts rate-limit holds.
        return new RetryTrigger(knowledgeId,
                cursors.retryFailedByKnowledge(knowledgeId),
                entities.retryFailedByKnowledge(knowledgeId));
    }
}
