package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.util.List;
import java.util.Optional;

/** Sending is the worker's job, so a caller never waits on a transport. */
public interface PublishingService {

    /**
     * @param dedupeKey null never collapses; otherwise a repeat with the same key returns the first delivery
     * @throws java.util.NoSuchElementException if no channel with {@code channelId} exists
     * @throws IllegalArgumentException if the message is empty
     */
    Delivery enqueue(String channelId, PublishMessage message, Delivery.Origin origin, String dedupeKey);

    Optional<Delivery> get(String id);

    /** Newest first; any filter may be null. */
    List<Delivery> list(String channelId, String refId, DeliveryStatus status, int limit, int offset);

    /**
     * @throws java.util.NoSuchElementException if no delivery with {@code id} exists
     * @throws IllegalStateException if it is not FAILED
     */
    Delivery retry(String id);
}
