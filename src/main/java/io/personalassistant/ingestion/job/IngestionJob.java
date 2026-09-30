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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Anything it cannot start this tick (no permit, lost the lease race) is retried next tick. Eligibility is
 * decided in the query, not after it: a skipped cursor is never written, so it stays at the head of the
 * least-recently-run order, and enough of them would fill every batch and stop all ingestion. The batch comes
 * from ACTIVE knowledges with a usable connection, plus PAUSED ones, which tryRun parks.
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

    // The permit is renewed per page with the lease, so its TTL must be at least app.ingestion.lease-seconds.
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
        List<String> eligible = eligibleKnowledgeIds();
        if (eligible.isEmpty()) {
            return;
        }
        for (Cursor candidate : cursors.findClaimable(eligible, pollBatch)) {
            tryRun(candidate);
        }
    }

    private List<String> eligibleKnowledgeIds() {
        List<String> ids = new ArrayList<>();
        for (Knowledge kn : knowledge.findByStatus(KnowledgeStatus.ACTIVE)) {
            if (!connectionUnusable(kn)) {
                ids.add(kn.id());
            }
        }
        for (Knowledge kn : knowledge.findByStatus(KnowledgeStatus.PAUSED)) {
            ids.add(kn.id());
        }
        return ids;
    }

    /**
     * Derived, not stored: nothing is paused, so a connection that works again resumes by itself. Any doubt
     * runs the knowledge; this is an optimisation and must never be the reason a sync stops.
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
            return; // orphan cursor: the delete path owns its removal
        }
        if (kn.get().status() != KnowledgeStatus.ACTIVE) {
            // Backstop: a cursor IN_PROGRESS at pause time rests AVAILABLE when its lease ends, so park the
            // knowledge's claimable cursors here too; resume() re-arms them.
            if (kn.get().status() == KnowledgeStatus.PAUSED) {
                cursors.suspendByKnowledge(candidate.knowledgeId());
            }
            return;
        }
        if (connectionUnusable(kn.get())) {
            return;
        }

        List<ScopeLimit> limits = List.of(
                ScopeLimit.global(globalMax),
                ScopeLimit.connector(kn.get().connectorDetails().type().name(), connectorMax),
                ScopeLimit.knowledge(kn.get().id(), knowledgeMax));

        Optional<Permit> permit = permits.tryAcquire(limits, worker, Duration.ofSeconds(permitTtlSeconds));
        if (permit.isEmpty()) {
            return; // at capacity for a scope: next tick
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
