package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Logger;

/**
 * Re-discovers iterables for connectors whose set can grow, so a new folder syncs without re-adding the
 * knowledge. Idempotent: it only ever adds cursors.
 */
@ApplicationScoped
public class IterableDiscoveryScheduler {

    private static final Logger LOG = Logger.getLogger(IterableDiscoveryScheduler.class.getName());

    private final KnowledgeRepository knowledge;
    private final ConnectorRegistry connectors;
    private final KnowledgeService knowledgeService;

    @Inject
    public IterableDiscoveryScheduler(KnowledgeRepository knowledge,
                                      ConnectorRegistry connectors,
                                      KnowledgeService knowledgeService) {
        this.knowledge = knowledge;
        this.connectors = connectors;
        this.knowledgeService = knowledgeService;
    }

    @Scheduled(every = "{app.scheduler.discovery-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (Knowledge kn : knowledge.findByStatus(KnowledgeStatus.ACTIVE)) {
            if (connectors.get(kn.connectorDetails().type()).hasDynamicIterables()) {
                int added = knowledgeService.reconcileCursors(kn.id());
                if (added > 0) {
                    LOG.info("Reconcile added " + added + " cursor(s) for knowledge " + kn.id());
                }
            }
        }
    }
}
