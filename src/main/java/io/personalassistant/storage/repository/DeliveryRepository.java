package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every write after a claim is compare-and-set on {@code lease.owner} plus a live lease: false means the
 * lease was lost, and the caller must stop touching the delivery.
 */
public interface DeliveryRepository {

    /**
     * Returns the existing delivery with the same non-null dedupeKey, untouched. Atomic on the unique index.
     */
    Delivery insertIfAbsent(Delivery delivery);

    Optional<Delivery> findById(String id);

    /** Newest first; any filter may be null. */
    List<Delivery> find(String channelId, String refId, DeliveryStatus status, int limit, int offset);

    /**
     * PENDING, backoff elapsed, no live lease. One at a time on purpose: claiming a batch starts every lease
     * together, so a slow first send lets the last lease lapse while it is still queued.
     */
    Optional<Delivery> claimNext(Collection<String> channelIds, String owner, Duration lease, Instant now);

    /** Resets attempts and drops the lease. Fenced. */
    boolean markSent(String id, String owner, String providerMessageId, Instant sentAt);

    /** Stays PENDING with the failure and a backoff. Fenced. */
    boolean markRetry(String id, String owner, String error, int attempts, Instant nextAttemptAt);

    /** Dead-letter. Fenced. */
    boolean markFailed(String id, String owner, String error, int attempts);

    /** Drops the lease without recording an attempt: the channel became unusable after the claim. */
    boolean release(String id, String owner);

    /**
     * Compare-and-set on the status.
     *
     * @return false if it does not exist or is not FAILED
     */
    boolean requeue(String id, Instant at);

    void deleteByChannel(String channelId);
}
