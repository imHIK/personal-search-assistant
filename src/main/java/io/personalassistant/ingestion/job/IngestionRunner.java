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

    @ConfigProperty(name = "app.ingestion.batches-per-lease", defaultValue = "50")  // number of iterations per cursor
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

    /**
     * Execute one lease over {@code cursor}. The {@code heartbeat} is invoked after each page so
     * the caller can renew any external lease (e.g. a permit). The cursor lease is renewed here.
     */
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
                    return;
                }
            }
            // Hit the batch cap with more pages remaining → re-pick next tick.
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
     * A rate-limited cursor is <em>held</em>, not failed: it rests {@code RATE_LIMITED} with the
     * reopening instant the limiter computed written as {@code nextAttemptAt}, and the claim filter
     * simply stops excluding it once that passes. No sweeper job flips it back — the timestamp is the
     * only state, so nothing can strand the cursor by dying.
     *
     * <p>The status is separate from {@code AVAILABLE} for the user's sake as much as the loop's.
     * Resting {@code AVAILABLE} meant a throttled cursor was re-picked every poll tick — cheap, since
     * {@code acquire} throws before sleeping and before any socket is opened, but indistinguishable in
     * the console from healthy work, and it burned one deferral per tick rather than one per genuine
     * reopening. Throttling is the one failure whose fix is usually the user's (raise the account's
     * limit), which it can only be if they can see it.
     *
     * <p>Replaying the page is safe by construction — {@code grab} is stateless and idempotent, and all
     * pagination state lives on the cursor (invariant 4).
     *
     * <p>Counted against {@code app.ratelimit.max-deferrals}, not {@code retry-limit}: being throttled
     * is the limiter working, not the source failing, and charging it to the ordinary retry budget would
     * park a healthy cursor {@code FAILED} after five throttled attempts. Now that each deferral is a
     * real reopening rather than a 30-second tick, that budget is a slow backstop for a limit set to
     * something unsatisfiable, not a ten-minute trap. Dead-lettering writes a null {@code nextAttemptAt}:
     * {@code FAILED} is outside the claim filter entirely, and leaving an instant behind would only be a
     * stale value for {@code retry-failed} to trip over.
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
     * The window that seeds a grab: forward walks {@code [anchor, +inf)} (incremental), backward walks
     * {@code (-inf, anchor)} (backfill). The connector receives this on {@link GrabContext#seedWindow()}
     * and never sees the direction; the runner keeps direction (on the cursor row) only to seed the
     * window here and to pick the resting status above.
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
        // Change detection: skip unchanged items the indexing stage already holds the current content
        // for. The skip must still touch the generation mark — otherwise a valid, unchanged file that a
        // membership re-walk re-saw would later look stale (lastSeenGeneration frozen below the
        // knowledge's current syncGeneration). Only write when it actually differs, so an ordinary poll
        // of an unchanged knowledge (generations equal) stays a pure no-op.
        //
        // The status test is "not FAILED or DELETED" rather than "is INDEXED" because INGESTED and
        // INDEXING already carry this exact content and sit in the work queue; re-upserting them is not
        // merely a wasted write. upsert() owns the queue reset, so it zeroes retry and clears
        // retry.nextAttemptAt — an entity deferred behind a rate limit would have its reopening instant
        // discarded on every poll, be re-claimed immediately, and be parsed and chunked again for
        // nothing until the window truly reopened. FAILED still falls through, so a poll is one of the
        // ways a dead letter gets another chance; DELETED falls through so an item that reappears at the
        // source with an unchanged checksum is re-ingested rather than left tombstoned.
        //
        // needsRefetch falls through too, and it is the only clause here that is not about the source:
        // it says the *stored* content is a staged copy we no longer trust, so "unchanged at the
        // source" is exactly the case it has to override. upsert() clears it in the same write that
        // stores the fresh bytes, so the escape is spent the moment it is used.
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
        // Below the skip, and only below it: this is where a connector that defers an expensive fetch
        // (Drive downloads a file, exports a doc) actually pays for it, so an unchanged item costs a
        // listing row and nothing else. A throw here propagates to runLease's catch and the page is
        // replayed — safe, since grab is idempotent and all pagination state is on the cursor.
        Entity.Content content = connector.materialize(kn, item);

        // The status/needsReindex/index/lease/retry arguments below are what upsert() guarantees
        // anyway — it owns the work-queue reset so that a re-ingest atomically fences out an indexer
        // still running on the previous revision. They are restated here only because Entity is the
        // carrier record; changing them here would not change what is stored.
        Entity entity = new Entity(id, kn.id(), cursor.iterableId(), item.entityType(),
                item.externalId(), item.raw(), content, item.metadata(), item.checksum(),
                EntityStatus.INGESTED, false, false, Entity.IndexInfo.empty(), null, Entity.Retry.zero(),
                createdAt, now,
                item.expiresAt(), // source-declared expiry, if any; else the knowledge window governs
                kn.syncGeneration()); // stamp the walk generation so re-walked items aren't seen as stale
        entities.upsert(entity);
    }

    Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
