package io.personalassistant.app;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.domain.service.PublishingService;
import io.personalassistant.storage.repository.ChannelRepository;
import io.personalassistant.storage.repository.DeliveryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The outbox's front door. Validates and writes rows; never sends — see
 * {@link io.personalassistant.publishing.job.DeliveryRunner} for that half.
 */
@ApplicationScoped
public class DefaultPublishingService implements PublishingService {

    private final ChannelRepository channels;
    private final DeliveryRepository deliveries;

    @Inject
    public DefaultPublishingService(ChannelRepository channels, DeliveryRepository deliveries) {
        this.channels = channels;
        this.deliveries = deliveries;
    }

    @Override
    public Delivery enqueue(String channelId, PublishMessage message, Delivery.Origin origin,
                            String dedupeKey) {
        channels.findById(channelId)
                .orElseThrow(() -> new NoSuchElementException("No channel with id " + channelId));
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("a message needs a title, an intro or at least one item");
        }
        // A disabled or ERROR channel still accepts the message. It waits in the queue rather than
        // being refused — pausing a channel or fixing its credentials should not lose what was sent
        // to it meanwhile.
        return deliveries.insertIfAbsent(Delivery.pending(Ids.delivery(), channelId, origin, dedupeKey,
                message, Instant.now()));
    }

    @Override
    public Optional<Delivery> get(String id) {
        return deliveries.findById(id);
    }

    @Override
    public List<Delivery> list(String channelId, String refId, DeliveryStatus status, int limit, int offset) {
        return deliveries.find(channelId, refId, status, limit, offset);
    }

    @Override
    public Delivery retry(String id) {
        Delivery delivery = deliveries.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No delivery with id " + id));
        if (!deliveries.requeue(id, Instant.now())) {
            throw new IllegalStateException("Delivery " + id + " is " + delivery.status()
                    + "; only a FAILED delivery can be retried");
        }
        return deliveries.findById(id).orElseThrow();
    }
}
