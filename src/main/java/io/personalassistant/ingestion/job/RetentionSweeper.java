package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.ingestion.retention.RetentionResolver;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Ages entities out, in two passes: entities whose source-declared {@code expiresAt} has passed, and
 * entities older than their knowledge's resolved retention window.
 *
 * <p>It only ever <strong>tombstones</strong> ({@code markDeleted}). Chunk removal is left to the
 * ordinary deletion path — {@code IndexingJob.processDeletions} claims the tombstone under a lease and
 * calls {@code deleteByEntity}. That indirection is the point: a native Mongo TTL index would drop the
 * document without ever telling OpenSearch, permanently orphaning its chunks, and would bypass lease
 * fencing entirely.
 *
 * <p><strong>Retention is opt-in.</strong> A knowledge that resolves to no window is skipped, and that
 * is the shipped default for every document source — so this job is inert until something positively
 * asks for a window. Deliberately <em>not</em> gated on connector health: if a feed has not been walked
 * in a week, its contents are stale whether or not the walk succeeded, and holding data back because
 * ingestion is broken would keep exactly the material the window exists to remove.
 */
@ApplicationScoped
public class RetentionSweeper {

    private static final Logger LOG = Logger.getLogger(RetentionSweeper.class.getName());

    private final EntityRepository entities;
    private final KnowledgeRepository knowledges;
    private final RetentionResolver retention;

    @ConfigProperty(name = "app.retention.batch", defaultValue = "200")
    int batch; // cap per pass per tick, so one huge knowledge can't stall the scheduler thread

    @Inject
    public RetentionSweeper(EntityRepository entities, KnowledgeRepository knowledges,
                            RetentionResolver retention) {
        this.entities = entities;
        this.knowledges = knowledges;
        this.retention = retention;
    }

    @Scheduled(every = "{app.retention.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        Instant now = Instant.now();
        try {
            sweepExplicitExpiry(now);
            sweepRetentionWindows(now);
        } catch (RuntimeException e) {
            // A sweep failure must not kill the scheduled job; the next tick retries from scratch
            // (the queries are stateless, so a partial sweep simply resumes).
            LOG.log(Level.WARNING, "Retention sweep failed; will retry on the next tick", e);
        }
    }

    /** Source-declared expiry. Applies to every knowledge, including those with no retention window. */
    private void sweepExplicitExpiry(Instant now) {
        tombstone(entities.findExpired(batch, now), "expiresAt elapsed");
    }

    /** The knowledge-level window, for entities that carry no expiry of their own. */
    private void sweepRetentionWindows(Instant now) {
        for (Knowledge kn : knowledges.findAll()) {
            if (kn.status() == KnowledgeStatus.DELETED) {
                continue;
            }
            Instant cutoff = retention.cutoffFor(kn, now);
            if (cutoff == null) {
                continue; // no window at any tier: this knowledge never expires
            }
            tombstone(entities.findCreatedBefore(kn.id(), cutoff, batch),
                    "older than retention window " + retention.resolve(kn));
        }
    }

    private void tombstone(List<Entity> expired, String reason) {
        if (expired.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Entity entity : expired) {
            entities.markDeleted(entity.id(), now);
        }
        LOG.info("Retention: tombstoned " + expired.size() + " entities (" + reason + ")");
    }

    /** Visible for tests that drive a sweep directly rather than through the scheduler. */
    void sweep(Instant now) {
        sweepExplicitExpiry(now);
        sweepRetentionWindows(now);
    }
}
