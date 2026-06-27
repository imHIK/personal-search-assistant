package io.personalassistant.ingestion.job;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.GrabRequest;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs a single leased cursor: pages the grabber up to {@code batchesPerLease} times, persists
 * each page as entities, advances the position after every page, and finally sets the cursor's
 * resting status. Extracted from {@link IngestionJob} so the page-loop logic is unit-testable
 * with in-memory fakes (no scheduler, no permits, no Mongo).
 *
 * <p>Direction-agnostic by design: the only place direction matters is the resting status — a
 * backward cursor that runs dry goes {@code EXHAUSTED}; a forward cursor goes {@code IDLE}.
 */
@ApplicationScoped
public class IngestionRunner {

    private static final Logger LOG = Logger.getLogger(IngestionRunner.class.getName());

    private final ConnectorRegistry connectors;
    private final EntityRepository entities;
    private final CursorRepository cursors;
    private final KnowledgeRepository knowledge;

    @ConfigProperty(name = "app.ingestion.batches-per-lease", defaultValue = "50")
    int batchesPerLease;

    @ConfigProperty(name = "app.ingestion.max-items-per-batch", defaultValue = "100")
    int maxItemsPerBatch;

    @ConfigProperty(name = "app.ingestion.lease-seconds", defaultValue = "900")
    long leaseSeconds;

    @ConfigProperty(name = "app.ingestion.retry-limit", defaultValue = "5")
    int retryLimit;

    @Inject
    public IngestionRunner(ConnectorRegistry connectors, EntityRepository entities,
                           CursorRepository cursors, KnowledgeRepository knowledge) {
        this.connectors = connectors;
        this.entities = entities;
        this.cursors = cursors;
        this.knowledge = knowledge;
    }

    /**
     * Execute one lease over {@code cursor}. The {@code heartbeat} is invoked after each page so
     * the caller can renew any external lease (e.g. a permit). The cursor lease is renewed here.
     */
    public void runLease(Knowledge kn, Cursor cursor, String worker, Runnable heartbeat) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());

        CursorPosition position = cursor.position() == null ? CursorPosition.start() : cursor.position();
        try {
            for (int batch = 0; batch < batchesPerLease; batch++) {
                GrabPage page = connector.grab(new GrabRequest(
                        kn, cursor.iterableId(), cursor.attributes(),
                        cursor.direction(), position, maxItemsPerBatch));

                long persisted = persistPage(kn, cursor, page.items());
                position = page.nextPosition();
                Instant now = Instant.now();

                // Persist progress AND renew the lease in one fenced write. If we no longer own the
                // lease (e.g. this page outran the TTL and another worker re-claimed the cursor),
                // bail out without releasing — the new owner continues from the persisted position.
                boolean stillOwned = cursors.advancePosition(
                        cursor.id(), worker, position, persisted, now, now.plusSeconds(leaseSeconds));
                if (!stillOwned) {
                    LOG.warning("Lost lease for cursor " + cursor.id()
                            + " mid-run; abandoning to the new owner");
                    return;
                }
                heartbeat.run();

                if (!page.hasMore()) {
                    CursorStatus resting = cursor.direction() == CursorDirection.BACKWARD
                            ? CursorStatus.EXHAUSTED   // history drained (terminal)
                            : CursorStatus.IDLE;        // caught up; scheduler re-arms it
                    cursors.release(cursor.id(), worker, resting);
                    refreshStats(kn.id());
                    return;
                }
            }
            // Hit the batch cap with more pages remaining → re-pick next tick.
            cursors.release(cursor.id(), worker, CursorStatus.AVAILABLE);
            refreshStats(kn.id());
        } catch (RuntimeException e) {
            int retryCount = cursor.retry().count() + 1;
            CursorStatus resting = retryCount > retryLimit ? CursorStatus.FAILED : CursorStatus.AVAILABLE;
            LOG.log(Level.WARNING, "Ingestion failed for cursor " + cursor.id()
                    + " (attempt " + retryCount + ", resting " + resting + ")", e);
            cursors.recordFailure(cursor.id(), worker, resting, retryCount);
        }
    }

    private long persistPage(Knowledge kn, Cursor cursor, List<RawItem> items) {
        long count = 0;
        for (RawItem item : items) {
            persistItem(kn, cursor, item);
            count++;
        }
        return count;
    }

    private void persistItem(Knowledge kn, Cursor cursor, RawItem item) {
        Optional<Entity> existing = entities.findByKnowledgeAndExternalId(kn.id(), item.externalId());

        if (item.deleted()) {
            existing.ifPresent(e -> entities.markDeleted(e.id(), Instant.now()));
            return;
        }
        // Change detection: skip unchanged items that are already up to date.
        if (existing.isPresent() && item.checksum() != null
                && item.checksum().equals(existing.get().checksum())
                && existing.get().status() == EntityStatus.INDEXED) {
            return;
        }

        Instant now = Instant.now();
        String id = existing.map(Entity::id).orElse(Ids.entity());
        Instant createdAt = existing.map(Entity::createdAt).orElse(now);
        Entity.IndexInfo index = existing.map(Entity::index).orElse(Entity.IndexInfo.empty());
        Entity.Content content = item.fileRef() != null
                ? Entity.Content.ofFile(item.fileRef())
                : Entity.Content.ofText(item.text());

        Entity entity = new Entity(id, kn.id(), cursor.iterableId(), item.entityType(),
                item.externalId(), item.raw(), content, item.metadata(), item.checksum(),
                EntityStatus.INGESTED, false, index, null, Entity.Retry.zero(), createdAt, now);
        entities.upsert(entity);
    }

    private void refreshStats(String knowledgeId) {
        if (knowledge.findById(knowledgeId).map(k -> k.status() == KnowledgeStatus.DELETED).orElse(true)) {
            return;
        }
        long total = entities.countByKnowledge(knowledgeId);
        long indexed = entities.countByKnowledgeAndStatus(knowledgeId, EntityStatus.INDEXED);
        long failed = entities.countByKnowledgeAndStatus(knowledgeId, EntityStatus.FAILED);
        knowledge.updateStats(knowledgeId, new Knowledge.Stats(total, indexed, failed));
    }

    Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
