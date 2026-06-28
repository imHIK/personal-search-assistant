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
import java.util.logging.Logger;

/**
 * Re-arms forward cursors so incremental sync keeps flowing. The core operation is tiny: flip a
 * knowledge's forward cursors {@code IDLE → AVAILABLE}; the normal ingestion loop then re-picks them
 * like any other cursor. Webhooks/manual sync trigger the same flip on demand via {@link #armNow}.
 *
 * <p><b>Per-source cadence.</b> The {@code tick} interval is only the scheduler's <em>check</em>
 * granularity. How often each knowledge is actually re-armed is governed by its resolved
 * {@link io.personalassistant.domain.model.SyncSchedule} (custom &rarr; connector default &rarr;
 * global default — see {@link ScheduleResolver}). Each tick arms only the knowledges whose
 * {@link Knowledge#nextSyncDueAt()} has arrived, then rolls that due time forward by the resolved
 * schedule. A {@code null} due time means "due now" (e.g. a freshly activated knowledge), so the
 * first tick after activation establishes the cadence.
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
            armAndReschedule(kn, now);
        }
    }

    private static boolean schedulingEnabled(Knowledge kn) {
        return kn.config() != null && kn.config().scheduleSettings() != null
                && kn.config().scheduleSettings().enabled();
    }

    /** Due when no due time is recorded yet (treat as "due now") or the recorded time has passed. */
    private static boolean isDue(Knowledge kn, Instant now) {
        Instant due = kn.nextSyncDueAt();
        return due == null || !due.isAfter(now);
    }

    /** Re-arm the knowledge's forward cursors and advance its next-due time by the resolved schedule. */
    private void armAndReschedule(Knowledge kn, Instant now) {
        int armed = cursors.armForwardCursors(kn.id());
        Instant next = schedules.nextDueAt(kn, now);
        knowledge.updateNextSyncDueAt(kn.id(), next);
        if (armed > 0) {
            LOG.fine("Re-armed " + armed + " forward cursor(s) for " + kn.id() + "; next due " + next);
        }
    }

    /**
     * Re-arm a single knowledge's forward cursors immediately, ignoring its schedule (used by
     * webhooks / on-demand sync). When the knowledge exists and scheduling is enabled, its next
     * scheduled due time is also rolled forward so the periodic tick doesn't immediately re-fire on
     * top of this manual arm.
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
