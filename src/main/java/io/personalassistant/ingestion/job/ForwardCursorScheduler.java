package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Logger;

/**
 * Re-arms forward cursors so incremental sync keeps flowing. It does the one tiny thing the
 * design calls for: flip a knowledge's forward cursors {@code IDLE → AVAILABLE}; the normal
 * ingestion loop then re-picks them like any other cursor. Webhooks trigger the same flip on
 * demand via {@link #armNow(String)}.
 *
 * <p>This first version re-arms on a fixed interval for every active, schedule-enabled knowledge.
 * Per-knowledge cron scheduling (driven by {@code scheduleSettings.cron}) is the next increment;
 * the re-arm primitive it relies on is already here.
 */
@ApplicationScoped
public class ForwardCursorScheduler {

    private static final Logger LOG = Logger.getLogger(ForwardCursorScheduler.class.getName());

    private final KnowledgeRepository knowledge;
    private final CursorRepository cursors;

    @Inject
    public ForwardCursorScheduler(KnowledgeRepository knowledge, CursorRepository cursors) {
        this.knowledge = knowledge;
        this.cursors = cursors;
    }

    @Scheduled(every = "{app.scheduler.forward-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (Knowledge kn : knowledge.findByStatus(KnowledgeStatus.ACTIVE)) {
            if (kn.config() != null && kn.config().scheduleSettings() != null
                    && kn.config().scheduleSettings().enabled()) {
                armNow(kn.id());
            }
        }
    }

    /** Re-arm a single knowledge's forward cursors immediately (used by the scheduler + webhooks). */
    public int armNow(String knowledgeId) {
        int armed = cursors.armForwardCursors(knowledgeId);
        if (armed > 0) {
            LOG.fine("Re-armed " + armed + " forward cursor(s) for " + knowledgeId);
        }
        return armed;
    }
}
