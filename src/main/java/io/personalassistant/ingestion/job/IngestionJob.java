package io.personalassistant.ingestion.job;

import io.personalassistant.common.concurrency.Permit;
import io.personalassistant.common.concurrency.PermitService;
import io.personalassistant.common.concurrency.ScopeLimit;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
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
    private final ConnectorRegistry connectors;
    private final ConnectionResolver connections;
    private final String worker = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    @ConfigProperty(name = "app.ingestion.poll-batch", defaultValue = "20")
    int pollBatch;

    @ConfigProperty(name = "app.ingestion.permits.global", defaultValue = "8")
    int globalMax;

    @ConfigProperty(name = "app.ingestion.permits.connector", defaultValue = "4")
    int connectorMax;

    @ConfigProperty(name = "app.ingestion.permits.knowledge", defaultValue = "2")
    int knowledgeMax;

    // Sized to the cursor lease: the permit is renewed per page alongside the lease, so it must
    // outlive a single page just as the lease does (keep >= app.ingestion.lease-seconds).
    @ConfigProperty(name = "app.ingestion.permits.ttl-seconds", defaultValue = "14400")
    long permitTtlSeconds;

    @Inject
    public IngestionJob(CursorRepository cursors, KnowledgeRepository knowledge,
                        PermitService permits, IngestionRunner runner,
                        ConnectorRegistry connectors, ConnectionResolver connections) {
        this.cursors = cursors;
        this.knowledge = knowledge;
        this.permits = permits;
        this.runner = runner;
        this.connectors = connectors;
        this.connections = connections;
    }

    @Scheduled(every = "{app.ingestion.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (Cursor candidate : cursors.findClaimable(pollBatch)) {
            tryRun(candidate);
        }
    }

    /**
     * Whether this knowledge's credentials are known to be bad, so running it would only burn a lease
     * and a permit to fail. {@code ConnectionHealthScheduler} is what marks a connection {@code ERROR};
     * an expired Google refresh token otherwise fails on every single tick, forever.
     *
     * <p>Deliberately derived rather than stored: nothing is paused and no knowledge state is written,
     * so a connection that starts working again resumes sync by itself — and a user's own pause is
     * never fought over. Any doubt (missing connection, unknown type, lookup failure) runs the
     * knowledge as before: this is an optimisation, and it must never be the reason a sync stops.
     */
    private boolean connectionUnusable(Knowledge kn) {
        try {
            var type = kn.connectorDetails().type();
            if (!connectors.supports(type) || !connectors.get(type).requiresConnection()) {
                return false;
            }
            return connections.resolve(kn).status() == ConnectionStatus.ERROR;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void tryRun(Cursor candidate) {
        Optional<Knowledge> kn = knowledge.findById(candidate.knowledgeId());
        if (kn.isEmpty()) {
            return; // orphan cursor — the delete path owns its removal
        }
        if (kn.get().status() != KnowledgeStatus.ACTIVE) {
            // Backstop: pause() parks a knowledge's cursors, but one that was IN_PROGRESS at pause
            // time rests AVAILABLE when its lease ends and would otherwise re-pollute the batch.
            // Park the whole knowledge's claimable cursors here too; resume() re-arms them.
            if (kn.get().status() == KnowledgeStatus.PAUSED) {
                cursors.suspendByKnowledge(candidate.knowledgeId());
            }
            return; // not active — don't run
        }
        if (connectionUnusable(kn.get())) {
            return; // credentials are known-bad; every page would fail
        }

        List<ScopeLimit> limits = List.of(
                ScopeLimit.global(globalMax),
                ScopeLimit.connector(kn.get().connectorDetails().type().name(), connectorMax),
                ScopeLimit.knowledge(kn.get().id(), knowledgeMax));

        Optional<Permit> permit = permits.tryAcquire(limits, worker, Duration.ofSeconds(permitTtlSeconds));
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
