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
 * Only tombstones: chunk removal goes through the ordinary leased deletion path. A Mongo TTL index would drop
 * documents without telling OpenSearch and bypass lease fencing. Not gated on connector health: a feed that
 * has not been walked is stale either way.
 */
@ApplicationScoped
public class RetentionSweeper {

    private static final Logger LOG = Logger.getLogger(RetentionSweeper.class.getName());

    private final EntityRepository entities;
    private final KnowledgeRepository knowledges;
    private final RetentionResolver retention;

    @ConfigProperty(name = "app.retention.batch", defaultValue = "200")
    int batch; // per pass per tick, so one huge knowledge cannot stall the scheduler thread

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
            // The queries are stateless, so the next tick resumes a partial sweep.
            LOG.log(Level.WARNING, "Retention sweep failed; will retry on the next tick", e);
        }
    }

    /** Applies to every knowledge, including those with no retention window. */
    private void sweepExplicitExpiry(Instant now) {
        tombstone(entities.findExpired(batch, now), "expiresAt elapsed");
    }

    private void sweepRetentionWindows(Instant now) {
        for (Knowledge kn : knowledges.findAll()) {
            if (kn.status() == KnowledgeStatus.DELETED) {
                continue;
            }
            Instant cutoff = retention.cutoffFor(kn, now);
            if (cutoff == null) {
                continue; // no window at any tier: never expires
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

    void sweep(Instant now) {
        sweepExplicitExpiry(now);
        sweepRetentionWindows(now);
    }
}
