package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flips a knowledge's forward cursors from IDLE to AVAILABLE when its nextSyncDueAt arrives (null is due
 * now), then rolls that forward by the resolved schedule. The tick is only the check granularity.
 */
@ApplicationScoped
public class ForwardCursorScheduler {

    private static final Logger LOG = Logger.getLogger(ForwardCursorScheduler.class.getName());

    private final KnowledgeRepository knowledge;
    private final CursorRepository cursors;
    private final ScheduleResolver schedules;

    @Inject
    public ForwardCursorScheduler(KnowledgeRepository knowledge, CursorRepository cursors,
                                  ScheduleResolver schedules) {
        this.knowledge = knowledge;
        this.cursors = cursors;
        this.schedules = schedules;
    }

    @Scheduled(every = "{app.scheduler.forward-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        Instant now = Instant.now();
        for (Knowledge kn : knowledge.findByStatus(KnowledgeStatus.ACTIVE)) {
            if (!schedulingEnabled(kn) || !isDue(kn, now)) {
                continue;
            }
            // One knowledge that cannot be rescheduled must not stop the rest: an escaping exception would
            // end the loop.
            try {
                armAndReschedule(kn, now);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not re-arm knowledge " + kn.id() + "; retrying next tick", e);
            }
        }
    }

    private static boolean schedulingEnabled(Knowledge kn) {
        return kn.config() != null && kn.config().scheduleSettings() != null
                && kn.config().scheduleSettings().enabled();
    }

    private static boolean isDue(Knowledge kn, Instant now) {
        Instant due = kn.nextSyncDueAt();
        return due == null || !due.isAfter(now);
    }

    private void armAndReschedule(Knowledge kn, Instant now) {
        int armed = cursors.armForwardCursors(kn.id());
        Instant next = schedules.nextDueAt(kn, now);
        knowledge.updateNextSyncDueAt(kn.id(), next);
        if (armed > 0) {
            LOG.fine("Re-armed " + armed + " forward cursor(s) for " + kn.id() + "; next due " + next);
        }
    }

    /**
     * Ignores the schedule, but rolls the next due time forward so the tick does not fire again on top of it.
     */
    public int armNow(String knowledgeId) {
        int armed = cursors.armForwardCursors(knowledgeId);
        knowledge.findById(knowledgeId)
                .filter(ForwardCursorScheduler::schedulingEnabled)
                .ifPresent(kn -> knowledge.updateNextSyncDueAt(kn.id(),
                        schedules.nextDueAt(kn, Instant.now())));
        if (armed > 0) {
            LOG.fine("Re-armed " + armed + " forward cursor(s) for " + knowledgeId);
        }
        return armed;
    }
}
