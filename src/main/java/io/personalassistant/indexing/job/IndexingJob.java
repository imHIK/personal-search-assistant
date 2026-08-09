package io.personalassistant.indexing.job;

import io.personalassistant.common.concurrency.Permit;
import io.personalassistant.common.concurrency.PermitService;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.storage.repository.EntityRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stage 2 driver. On each tick it acquires a global indexing permit, cleans up any tombstoned
 * entities, then indexes entities <strong>fairly across knowledges</strong>: it finds the distinct
 * knowledges with pending work and claims a small per-knowledge quota in round-robin order, up to a
 * global per-tick budget. This means one knowledge's huge backlog can't monopolize the loop and
 * starve the others — without spawning a job per knowledge. Claims are leased, so a crash mid-run is
 * reclaimed once the lease lapses.
 */
@ApplicationScoped
public class IndexingJob {

    private final EntityRepository entities;
    private final PermitService permits;
    private final IndexingRunner runner;
    private final String worker = "indexer-" + UUID.randomUUID().toString().substring(0, 8);

    /** Rotating offset so a different knowledge leads the round-robin each tick (fairness). */
    private int rotation;

    @ConfigProperty(name = "app.indexing.batch", defaultValue = "20")
    int batch; // global budget of entities indexed per tick

    @ConfigProperty(name = "app.indexing.per-knowledge", defaultValue = "5")
    int perKnowledge; // max entities claimed from any single knowledge per tick

    @ConfigProperty(name = "app.indexing.max-knowledges", defaultValue = "200")
    int maxKnowledges; // cap on distinct knowledges scanned per tick

    @ConfigProperty(name = "app.indexing.concurrency", defaultValue = "4")
    int concurrency;

    // The permit is held for a whole tick (not renewed mid-tick), so it must exceed the worst-case
    // time to process one tick's batch — comfortably above the per-entity lease.
    @ConfigProperty(name = "app.indexing.permits.ttl-seconds", defaultValue = "1200")
    long permitTtlSeconds;

    @Inject
    public IndexingJob(EntityRepository entities, PermitService permits, IndexingRunner runner) {
        this.entities = entities;
        this.permits = permits;
        this.runner = runner;
    }

    @Scheduled(every = "{app.indexing.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        Optional<Permit> permit = permits.tryAcquire("indexing:global", concurrency, worker,
                Duration.ofSeconds(permitTtlSeconds));
        if (permit.isEmpty()) {
            return; // another worker is already at the indexing concurrency ceiling
        }
        try {
            processDeletions();
            processIndexingFairly();
        } finally {
            permits.release(permit.get());
        }
    }

    /**
     * Round-robin over the knowledges that have pending work, claiming up to {@code perKnowledge}
     * each until the global {@code batch} budget is spent. The {@code rotation} offset advances each
     * tick so the knowledge that goes first keeps changing — no fixed ordering can starve a tail.
     */
    private void processIndexingFairly() {
        List<String> pending = entities.distinctPendingKnowledgeIds(maxKnowledges);
        if (pending.isEmpty()) {
            return;
        }
        int start = Math.floorMod(rotation++, pending.size());
        int budget = batch;
        for (int i = 0; i < pending.size() && budget > 0; i++) {
            String knowledgeId = pending.get((start + i) % pending.size());
            int quota = Math.min(perKnowledge, budget);
            List<Entity> claimed = entities.claimForIndexing(knowledgeId, quota, worker, runner.leaseDuration());
            for (Entity entity : claimed) {
                runner.indexEntity(entity, worker);
            }
            budget -= claimed.size();
        }
    }

    private void processDeletions() {
        List<Entity> tombstoned = entities.claimForDeletion(batch, worker, runner.leaseDuration());
        for (Entity entity : tombstoned) {
            runner.deleteEntityChunks(entity, worker);
        }
    }
}
