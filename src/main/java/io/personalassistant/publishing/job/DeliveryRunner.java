package io.personalassistant.publishing.job;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.publishing.ChannelConnectionResolver;
import io.personalassistant.publishing.PublishException;
import io.personalassistant.publishing.PublishReceipt;
import io.personalassistant.publishing.Publisher;
import io.personalassistant.publishing.PublisherRegistry;
import io.personalassistant.storage.repository.ChannelRepository;
import io.personalassistant.storage.repository.DeliveryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Claims deliveries only for ready channels (enabled, ACTIVE, and with a resolvable ACTIVE account when the
 * publisher needs one), so a blocked queue spends no attempts. Derived, not stored: a reconnected account
 * releases its channels' queues on the next tick.
 */
@ApplicationScoped
public class DeliveryRunner {

    private static final Logger LOG = Logger.getLogger(DeliveryRunner.class.getName());

    private final String worker = "publisher-" + UUID.randomUUID().toString().substring(0, 8);

    private final ChannelRepository channels;
    private final DeliveryRepository deliveries;
    private final PublisherRegistry publishers;
    private final ChannelConnectionResolver resolver;

    @ConfigProperty(name = "app.publishing.batch", defaultValue = "20")
    int batch;

    @ConfigProperty(name = "app.publishing.lease-seconds", defaultValue = "120")
    long leaseSeconds;

    @ConfigProperty(name = "app.publishing.retry-limit", defaultValue = "5")
    int retryLimit;

    @ConfigProperty(name = "app.publishing.backoff-seconds", defaultValue = "60")
    long backoffSeconds;

    @ConfigProperty(name = "app.publishing.max-backoff-seconds", defaultValue = "3600")
    long maxBackoffSeconds;

    @Inject
    public DeliveryRunner(ChannelRepository channels, DeliveryRepository deliveries,
                          PublisherRegistry publishers, ChannelConnectionResolver resolver) {
        this.channels = channels;
        this.deliveries = deliveries;
        this.publishers = publishers;
        this.resolver = resolver;
    }

    private record Ready(Channel channel, Publisher publisher, Connection connection) {
    }

    public int runOnce() {
        Set<String> ready = new HashSet<>();
        for (Channel channel : channels.findUsable()) {
            if (ready(channel) != null) {
                ready.add(channel.id());
            }
        }
        int sent = 0;
        for (int i = 0; i < batch && !ready.isEmpty(); i++) {
            Optional<Delivery> claimed = deliveries.claimNext(ready, worker,
                    Duration.ofSeconds(leaseSeconds), Instant.now());
            if (claimed.isEmpty()) {
                break;
            }
            Delivery delivery = claimed.get();
            // Re-resolved per delivery: an earlier send this tick may have parked the channel or broken its
            // account.
            Ready target = ready(channels.findById(delivery.channelId()).orElse(null));
            if (target == null) {
                ready.remove(delivery.channelId());
                deliveries.release(delivery.id(), worker);
                continue;
            }
            if (deliver(delivery, target, ready)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * @return null when it cannot send now. A missing or wrong-type account parks the channel, since that
     *         needs a person; a merely broken one is skipped, since reconnecting it is the fix
     */
    private Ready ready(Channel channel) {
        if (channel == null || !channel.usable()) {
            return null;
        }
        try {
            Publisher publisher = publishers.get(channel.type());
            Connection connection = resolver.resolve(channel, publisher);
            return ChannelConnectionResolver.usable(connection) ? new Ready(channel, publisher, connection) : null;
        } catch (RuntimeException e) {
            park(channel, e.getMessage());
            return null;
        }
    }

    private boolean deliver(Delivery delivery, Ready target, Set<String> ready) {
        Channel channel = target.channel();
        try {
            PublishReceipt receipt = target.publisher()
                    .publish(channel, target.connection(), delivery.message(), delivery.id());
            if (!deliveries.markSent(delivery.id(), worker,
                    receipt == null ? null : receipt.providerMessageId(), Instant.now())) {
                // Sent, but a new owner holds the row and will send it again: the at-least-once window.
                LOG.warning("Lost the lease on delivery " + delivery.id() + " before markSent; "
                        + "it may be delivered twice");
            }
            return true;
        } catch (PublishException e) {
            if (e.permanent()) {
                // The channel's fault, not this message's: park the channel so its other deliveries stop
                // spending attempts; a successful test brings it back.
                LOG.warning("Channel " + channel.id() + " refused delivery " + delivery.id()
                        + " permanently; parking the channel: " + e.getMessage());
                park(channel, e.getMessage());
                ready.remove(channel.id());
            } else {
                LOG.log(Level.INFO, "Delivery " + delivery.id() + " failed; will retry", e);
            }
            recordFailure(delivery, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Delivery " + delivery.id() + " failed unexpectedly; will retry", e);
            recordFailure(delivery, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private void park(Channel channel, String reason) {
        channels.updateStatus(channel.id(), ChannelStatus.ERROR, reason, Instant.now());
    }

    /** Consecutive: markSent zeroes the count, so the limit means failures in a row. */
    private void recordFailure(Delivery delivery, String error) {
        int attempts = delivery.attempts() + 1;
        boolean written = attempts >= retryLimit
                ? deliveries.markFailed(delivery.id(), worker, error, attempts)
                : deliveries.markRetry(delivery.id(), worker, error, attempts,
                        Instant.now().plus(backoff(attempts)));
        if (!written) {
            LOG.warning("Lost the lease on delivery " + delivery.id()
                    + " before recording a failure; the new owner will record its own outcome");
        }
    }

    /** Doubling from backoff-seconds, capped: a platform down for an hour is common. */
    Duration backoff(int attempts) {
        long seconds = Math.max(backoffSeconds, 1);
        for (int i = 1; i < attempts && seconds < maxBackoffSeconds; i++) {
            seconds *= 2;
        }
        return Duration.ofSeconds(Math.min(seconds, Math.max(maxBackoffSeconds, 1)));
    }
}
