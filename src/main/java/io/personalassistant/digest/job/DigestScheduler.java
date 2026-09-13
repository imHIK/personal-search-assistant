package io.personalassistant.digest.job;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.service.DigestService;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.storage.repository.DigestRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wakes periodically and runs whichever digests are due, following the same shape as the ingestion
 * schedulers: a fixed tick that bounds resolution, with each digest's own cadence stored as a due time
 * it rolls forward. The tick is therefore the finest granularity available, not the run frequency.
 *
 * <p>Cadence is resolved through the same {@link ScheduleResolver} the ingestion side uses, so a digest
 * accepts the cron and interval forms already documented rather than inventing a second syntax.
 */
@ApplicationScoped
public class DigestScheduler {

    private static final Logger LOG = Logger.getLogger(DigestScheduler.class.getName());

    private final DigestRepository digests;
    private final DigestService service;
    private final ScheduleResolver schedules;

    @ConfigProperty(name = "app.digest.batch", defaultValue = "10")
    int batch; // digests run per tick, so a burst of due digests cannot monopolise the scheduler thread

    @Inject
    public DigestScheduler(DigestRepository digests, DigestService service, ScheduleResolver schedules) {
        this.digests = digests;
        this.service = service;
        this.schedules = schedules;
    }

    @Scheduled(every = "{app.digest.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        Instant now = Instant.now();
        List<Digest> due;
        try {
            due = digests.findDue(now, batch);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not list due digests; retrying next tick", e);
            return;
        }
        for (Digest digest : due) {
            // The due time is rolled forward FIRST. A digest whose run throws would otherwise stay due
            // and be retried every tick — turning one broken digest into a hot loop against the LLM.
            // DigestService.run records failures rather than throwing, so this is belt and braces.
            rollForward(digest, now);
            try {
                service.run(digest.id());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Digest " + digest.id() + " failed to run", e);
            }
        }
    }

    private void rollForward(Digest digest, Instant from) {
        // Computing the due time sits inside the try with the save: thrown from here it would end the
        // loop, and every digest after this one would miss its run.
        try {
            SyncSchedule schedule = digest.schedule();
            Instant next = schedule != null && schedule.isPresent()
                    ? schedules.nextDueAt(schedule, from)
                    // No cadence of its own: fall back to the global default rather than leaving it
                    // permanently due, which would run it every tick.
                    : schedules.nextDueAt(schedules.globalDefault(), from);
            digests.save(digest.withNextRunAt(next));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not advance the due time for digest " + digest.id(), e);
        }
    }
}
