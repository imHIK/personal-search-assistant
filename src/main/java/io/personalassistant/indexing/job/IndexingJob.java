package io.personalassistant.indexing.job;

import io.personalassistant.common.concurrency.Permit;
import io.personalassistant.common.concurrency.PermitService;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.storage.repository.EntityRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stage 2 driver. On each tick it acquires a global indexing permit, then atomically claims a
 * batch of entities to (re)index and a batch of tombstoned entities to clean up, handing each to
 * the {@link IndexingRunner}. Claims are leased, so a crash mid-run is reclaimed once the lease
 * lapses. Like ingestion, this is Mongo-polling for now behind a stable stage boundary.
 */
@ApplicationScoped
public class IndexingJob {

    private final EntityRepository entities;
    private final PermitService permits;
    private final IndexingRunner runner;
    private final String worker = "indexer-" + UUID.randomUUID().toString().substring(0, 8);

    @ConfigProperty(name = "app.indexing.batch", defaultValue = "20")
    int batch;

    @ConfigProperty(name = "app.indexing.concurrency", defaultValue = "4")
    int concurrency;

    @Inject
    public IndexingJob(EntityRepository entities, PermitService permits, IndexingRunner runner) {
        this.entities = entities;
        this.permits = permits;
        this.runner = runner;
    }

    @Scheduled(every = "{app.indexing.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        Optional<Permit> permit = permits.tryAcquire("indexing:global", concurrency, worker);
        if (permit.isEmpty()) {
            return; // another worker is already at the indexing concurrency ceiling
        }
        try {
            processDeletions();
            processIndexing();
        } finally {
            permits.release(permit.get());
        }
    }

    private void processIndexing() {
        List<Entity> claimed = entities.claimForIndexing(batch, worker, runner.leaseDuration());
        for (Entity entity : claimed) {
            runner.indexEntity(entity);
        }
    }

    private void processDeletions() {
        List<Entity> tombstoned = entities.claimForDeletion(batch, worker, runner.leaseDuration());
        for (Entity entity : tombstoned) {
            runner.deleteEntityChunks(entity);
        }
    }
}
