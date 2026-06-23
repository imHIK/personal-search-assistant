package io.personalassistant.ingestion.job;

import io.personalassistant.common.concurrency.Permit;
import io.personalassistant.common.concurrency.PermitService;
import io.personalassistant.common.concurrency.ScopeLimit;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stage 1 driver. On each tick it pulls a batch of claimable cursors and, for each, tries to
 * acquire a scoped concurrency permit, atomically lease the cursor, and hand it to the
 * {@link IngestionRunner}. Direction is irrelevant here — backward and forward cursors are
 * treated identically; the runner decides the resting status.
 *
 * <p>The poll loop is intentionally simple: anything it can't start this tick (no permit, lost
 * the lease race) is simply retried next tick. Job mechanism is Mongo polling for now; the stage
 * boundary keeps a later swap to a broker from touching connectors.
 */
@ApplicationScoped
public class IngestionJob {

    private static final Logger LOG = Logger.getLogger(IngestionJob.class.getName());

    private final CursorRepository cursors;
    private final KnowledgeRepository knowledge;
    private final PermitService permits;
    private final IngestionRunner runner;
    private final String worker = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    @ConfigProperty(name = "app.ingestion.poll-batch", defaultValue = "20")
    int pollBatch;

    @ConfigProperty(name = "app.ingestion.permits.global", defaultValue = "8")
    int globalMax;

    @ConfigProperty(name = "app.ingestion.permits.connector", defaultValue = "4")
    int connectorMax;

    @ConfigProperty(name = "app.ingestion.permits.knowledge", defaultValue = "2")
    int knowledgeMax;

    @Inject
    public IngestionJob(CursorRepository cursors, KnowledgeRepository knowledge,
                        PermitService permits, IngestionRunner runner) {
        this.cursors = cursors;
        this.knowledge = knowledge;
        this.permits = permits;
        this.runner = runner;
    }

    @Scheduled(every = "{app.ingestion.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (Cursor candidate : cursors.findClaimable(pollBatch)) {
            tryRun(candidate);
        }
    }

    private void tryRun(Cursor candidate) {
        Optional<Knowledge> kn = knowledge.findById(candidate.knowledgeId());
        if (kn.isEmpty() || kn.get().status() != KnowledgeStatus.ACTIVE) {
            return; // paused/deleted/unknown knowledge — leave the cursor be
        }

        List<ScopeLimit> limits = List.of(
                ScopeLimit.global(globalMax),
                ScopeLimit.connector(kn.get().connectorDetails().type().name(), connectorMax),
                ScopeLimit.knowledge(kn.get().id(), knowledgeMax));

        Optional<Permit> permit = permits.tryAcquire(limits, worker);
        if (permit.isEmpty()) {
            return; // at capacity for one of the scopes — try again next tick
        }
        try {
            Optional<Cursor> leased = cursors.claim(candidate.id(), worker, runner.leaseDuration());
            if (leased.isEmpty()) {
                return; // another worker won the race
            }
            runner.runLease(kn.get(), leased.get(), worker, () -> permits.renew(permit.get()));
        } catch (RuntimeException e) {
            LOG.warning("Unexpected ingestion error on cursor " + candidate.id() + ": " + e.getMessage());
        } finally {
            permits.release(permit.get());
        }
    }
}
