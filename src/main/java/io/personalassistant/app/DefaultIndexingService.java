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
 * Thin orchestration for the manual indexing actions. Triggering a sync re-arms forward cursors
 * (the continuous job then does the work); delete simply sets a flag the indexing job acts on.
 *
 * <p>Re-index is the one action with real work behind it, and only for connectors that stage a copy
 * of their content ({@link RefetchPolicy}). Those get the content fetched again first, by two
 * different routes depending on scale: one entity is fetched here and now, because a walk pages
 * through a source and cannot be asked for a single known id; a whole knowledge is flagged and its
 * cursors rewound, because the ingestion walk already has the leases, permits and rate limiting that
 * issuing thousands of downloads from an HTTP thread would not.
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
     * Re-list one item and store what comes back. The upsert does the whole queue transition — status
     * {@code INGESTED}, flags cleared, retry zeroed, lease dropped — so the indexing job claims the
     * entity on its next tick and nothing else has to be flagged.
     *
     * <p>The refreshed checksum matters as much as the refreshed content: the next walk compares
     * against it, so storing the item exactly as {@code grab} would have shaped it is what stops this
     * looking like a source-side change on every subsequent poll.
     */
    private void refetch(Knowledge kn, Entity entity) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());
        Optional<RawItem> fetched;
        try {
            fetched = connector.fetchOne(kn, entity);
        } catch (RateLimitedException e) {
            // Surfaced as a conflict rather than a 500: the caller can act on it (wait, or raise the
            // quota and retry), which a raw stack trace does not tell them.
            throw new IllegalStateException("Rate limited by " + e.key()
                    + "; re-index of entity " + entity.id() + " can be retried after " + e.retryAt(), e);
        }
        if (fetched.isEmpty() || fetched.get().deleted()) {
            // Gone at the source. Tombstoning is the honest outcome and matches what a walk would do;
            // re-indexing stale content because the fetch came back empty would not.
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
            // Flag before rewinding, never after: a cursor re-armed first could already be walking
            // pages by the time the flag lands, and every item on those pages would take the
            // unchanged-checksum skip and keep its stale content reference.
            refetching = entities.flagNeedsRefetchByKnowledge(knowledgeId);
            cursorsReset = rewindForRefetch(kn);
        }
        LOG.info("Queued " + queued + " entities of knowledge " + knowledgeId + " for re-indexing"
                + (refetching > 0 ? " (" + refetching + " to be re-fetched first, across "
                + cursorsReset + " rewound cursor(s))" : ""));
        return new ReindexTrigger(knowledgeId, queued, refetching, cursorsReset);
    }

    /**
     * Rewind the knowledge's cursors so the walk re-lists everything it has already covered — the
     * other half of the flag, since the forward cursor's high-water floor is precisely what stops an
     * unmodified item from ever being offered again.
     *
     * <p>Two cursors are left alone. {@code RETIRED} ones belong to iterables that disappeared at the
     * source, so re-walking them would fail or resurrect parked data. A {@code BACKWARD} cursor is
     * rewound only when backfill is still enabled or it has already drained: a backfill the user
     * turned off part-way would otherwise silently restart and keep going past where it stopped.
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
        // Both halves, because both stages dead-letter independently: a cursor can exhaust its
        // retries fetching while entities fail to index, and a user asking to "retry what failed"
        // means all of it. The cursor half also lifts rate-limit holds, which is the console's only
        // way to act on a RATE_LIMITED cursor after raising the account's quota.
        return new RetryTrigger(knowledgeId,
                cursors.retryFailedByKnowledge(knowledgeId),
                entities.retryFailedByKnowledge(knowledgeId));
    }
}
