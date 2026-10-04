package io.personalassistant.ingestion.job;

import io.personalassistant.common.Errors;
import io.personalassistant.common.id.Ids;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.EntityRepository;
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
 * Direction matters only for the resting status: a backward cursor that runs dry goes EXHAUSTED, a forward
 * one IDLE.
 */
@ApplicationScoped
public class IngestionRunner {

    private static final Logger LOG = Logger.getLogger(IngestionRunner.class.getName());

    private final ConnectorRegistry connectors;
    private final EntityRepository entities;
    private final CursorRepository cursors;

    @ConfigProperty(name = "app.ingestion.batches-per-lease", defaultValue = "50")  // pages per lease
    public int batchesPerLease;

    @ConfigProperty(name = "app.ingestion.max-items-per-batch", defaultValue = "100")
    public int maxItemsPerBatch;

    @ConfigProperty(name = "app.ingestion.lease-seconds", defaultValue = "900")
    public long leaseSeconds;

    @ConfigProperty(name = "app.ingestion.retry-limit", defaultValue = "5")
    public int retryLimit;

    @ConfigProperty(name = "app.ratelimit.max-deferrals", defaultValue = "20")
    public int maxDeferrals;

    @Inject
    public IngestionRunner(ConnectorRegistry connectors, EntityRepository entities,
                           CursorRepository cursors) {
        this.connectors = connectors;
        this.entities = entities;
        this.cursors = cursors;
    }

    /** {@code heartbeat} runs after each page, so the caller can renew an external lease such as a permit. */
    public void runLease(Knowledge kn, Cursor cursor, String worker, Runnable heartbeat) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());

        CursorPosition position = cursor.position() == null ? CursorPosition.start() : cursor.position();
        TimeWindow seedWindow = seedWindow(cursor.direction(), kn.anchor());
        try {
            for (int batch = 0; batch < batchesPerLease; batch++) {
                GrabResult page = connector.grab(new GrabContext(
                        kn, cursor.iterableId(), cursor.attributes(),
                        position, seedWindow, maxItemsPerBatch));

                long persisted = persistPage(kn, cursor, connector, page.items());
                position = page.cursor();
                Instant now = Instant.now();

                // Progress and lease renewal in one fenced write. If the lease is lost, bail out without
                // releasing: the new owner continues from the persisted position.
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
                            ? CursorStatus.EXHAUSTED   // history drained
                            : CursorStatus.IDLE;        // caught up; the scheduler re-arms it
                    cursors.release(cursor.id(), worker, resting);
                    return;
                }
            }
            // At the batch cap with pages remaining: re-picked next tick.
            cursors.release(cursor.id(), worker, CursorStatus.AVAILABLE);
        } catch (RateLimitedException e) {
            defer(cursor, worker, e);
        } catch (RuntimeException e) {
            int retryCount = cursor.retry().count() + 1;
            CursorStatus resting = retryCount > retryLimit ? CursorStatus.FAILED : CursorStatus.AVAILABLE;
            LOG.log(Level.WARNING, "Ingestion failed for cursor " + cursor.id()
                    + " (attempt " + retryCount + ", resting " + resting + ")", e);
            cursors.recordFailure(cursor.id(), worker, resting, retryCount, Errors.summary(e), null);
        }
    }

    /**
     * Held, not failed: it rests RATE_LIMITED until the limiter's nextAttemptAt, which the claim filter
     * checks, so no sweeper exists to strand it. A separate status so the console can show throttling, whose
     * fix is usually the user's. Counted against {@code app.ratelimit.max-deferrals}, not the retry limit, so
     * a throttled healthy cursor is never dead-lettered; dead-lettering clears nextAttemptAt.
     */
    private void defer(Cursor cursor, String worker, RateLimitedException e) {
        int count = cursor.retry().count() + 1;
        boolean dead = count > maxDeferrals;
        CursorStatus resting = dead ? CursorStatus.FAILED : CursorStatus.RATE_LIMITED;
        String reason = "Rate limited on " + e.key() + "; will retry after " + e.retryAt()
                + " (" + count + " consecutive deferrals)";
        if (dead) {
            LOG.warning("Cursor " + cursor.id() + " has been rate limited " + count
                    + " times in a row on " + e.key() + "; parking it FAILED. Raise the limit on that"
                    + " account, or app.ratelimit.max-deferrals.");
        } else {
            LOG.fine(() -> reason + " for cursor " + cursor.id());
        }
        cursors.recordFailure(cursor.id(), worker, resting, count, reason, dead ? null : e.retryAt());
    }

    /**
     * Forward walks {@code [anchor, +inf)}, backward {@code (-inf, anchor)}: the connector gets the window,
     * never the direction.
     */
    private static TimeWindow seedWindow(CursorDirection direction, Instant anchor) {
        return direction == CursorDirection.BACKWARD
                ? TimeWindow.before(anchor)
                : TimeWindow.atOrAfter(anchor);
    }

    private long persistPage(Knowledge kn, Cursor cursor, SourceConnector connector, List<RawItem> items) {
        long count = 0;
        for (RawItem item : items) {
            persistItem(kn, cursor, connector, item);
            count++;
        }
        return count;
    }

    private void persistItem(Knowledge kn, Cursor cursor, SourceConnector connector, RawItem item) {
        Optional<Entity> existing = entities.findByKnowledgeAndExternalId(kn.id(), item.externalId());

        if (item.deleted()) {
            existing.ifPresent(e -> entities.markDeleted(e.id(), Instant.now()));
            return;
        }
        // Skip items whose current content the index already holds. The test is not FAILED or DELETED rather
        // than INDEXED: INGESTED and INDEXING already carry this content, and re-upserting would reset the
        // indexer's retry and nextAttemptAt. FAILED falls through as a second chance, DELETED so a
        // reappearing item is re-ingested, and needsRefetch because it distrusts the stored copy. The skip
        // still stamps the generation, writing only when it differs.
        if (existing.isPresent() && item.checksum() != null
                && item.checksum().equals(existing.get().checksum())
                && !existing.get().needsRefetch()
                && existing.get().status() != EntityStatus.FAILED
                && existing.get().status() != EntityStatus.DELETED) {
            if (existing.get().lastSeenGeneration() != kn.syncGeneration()) {
                entities.stampLastSeen(existing.get().id(), kn.syncGeneration());
            }
            return;
        }

        Instant now = Instant.now();
        String id = existing.map(Entity::id).orElse(Ids.entity());
        Instant createdAt = existing.map(Entity::createdAt).orElse(now);
        // Below the skip on purpose: this is where a deferred expensive fetch is paid, so an unchanged item
        // costs only a listing row. A throw replays the page.
        Entity.Content content = connector.materialize(kn, item);

        // upsert() owns the work-queue reset; these arguments only restate what it stores.
        Entity entity = new Entity(id, kn.id(), cursor.iterableId(), item.entityType(),
                item.externalId(), item.raw(), content, item.metadata(), item.checksum(),
                EntityStatus.INGESTED, false, false, Entity.IndexInfo.empty(), null, Entity.Retry.zero(),
                createdAt, now,
                item.expiresAt(), // source-declared expiry, if any; else the knowledge window governs
                kn.syncGeneration()); // stamp the walk generation
        entities.upsert(entity);
    }

    Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
