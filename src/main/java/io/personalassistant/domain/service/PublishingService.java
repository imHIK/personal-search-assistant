package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.util.List;
import java.util.Optional;

/**
 * Use-case port for putting messages in the outbox and reading it back. Sending is not here: that is
 * the delivery worker's, so nothing that calls this ever waits on a transport.
 */
public interface PublishingService {

    /**
     * Queue a message for a channel. Returns immediately; a worker sends it.
     *
     * @param dedupeKey null for a send that must never collapse; otherwise a second call with the same
     *                  key returns the first delivery instead of queuing another
     * @throws java.util.NoSuchElementException if no channel with {@code channelId} exists
     * @throws IllegalArgumentException         if the message is empty
     */
    Delivery enqueue(String channelId, PublishMessage message, Delivery.Origin origin, String dedupeKey);

    Optional<Delivery> get(String id);

    /** Newest first; any filter may be null. {@code refId} selects what one producer queued, e.g. a digest run. */
    List<Delivery> list(String channelId, String refId, DeliveryStatus status, int limit, int offset);

    /**
     * Put a dead-lettered delivery back in the queue with its attempts reset.
     *
     * @throws java.util.NoSuchElementException if no delivery with {@code id} exists
     * @throws IllegalStateException            if it is not {@code FAILED}
     */
    Delivery retry(String id);
}
