package io.personalassistant.publishing.job;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The tick bounds how long a fresh message waits for its first attempt; retries follow each delivery's
 * nextAttemptAt. SKIP stops overlapping ticks in one process; the delivery lease would keep two processes
 * apart.
 */
@ApplicationScoped
public class PublishingScheduler {

    private static final Logger LOG = Logger.getLogger(PublishingScheduler.class.getName());

    private final DeliveryRunner runner;

    @Inject
    public PublishingScheduler(DeliveryRunner runner) {
        this.runner = runner;
    }

    @Scheduled(every = "{app.publishing.poll-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            int sent = runner.runOnce();
            if (sent > 0) {
                LOG.fine(() -> "Published " + sent + " deliveries");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Publishing tick failed; retrying next tick", e);
        }
    }
}
