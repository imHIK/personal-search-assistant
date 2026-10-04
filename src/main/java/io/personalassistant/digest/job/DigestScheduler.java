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
 * Runs due digests on a fixed tick, which is the finest granularity; each digest's cadence is a due time
 * rolled forward.
 */
@ApplicationScoped
public class DigestScheduler {

    private static final Logger LOG = Logger.getLogger(DigestScheduler.class.getName());

    private final DigestRepository digests;
    private final DigestService service;
    private final ScheduleResolver schedules;

    @ConfigProperty(name = "app.digest.batch", defaultValue = "10")
    int batch; // per tick, so a burst of due digests cannot hold the scheduler thread

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
            // Roll the due time forward first: a digest whose run throws would otherwise stay due and hit the
            // LLM every tick.
            rollForward(digest, now);
            try {
                service.run(digest.id());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Digest " + digest.id() + " failed to run", e);
            }
        }
    }

    private void rollForward(Digest digest, Instant from) {
        // Inside the try: thrown from here it would end the loop, and every later digest would miss its run.
        try {
            SyncSchedule schedule = digest.schedule();
            Instant next = schedule != null && schedule.isPresent()
                    ? schedules.nextDueAt(schedule, from)
                    // No cadence of its own: the global default, rather than staying due and running every
                    // tick.
                    : schedules.nextDueAt(schedules.globalDefault(), from);
            digests.save(digest.withNextRunAt(next));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not advance the due time for digest " + digest.id(), e);
        }
    }
}
