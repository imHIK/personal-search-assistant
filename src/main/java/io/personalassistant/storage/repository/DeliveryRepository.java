package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@code deliveries} collection — the publishing outbox.
 *
 * <p>Every write a worker makes after claiming ({@link #markSent}, {@link #markRetry},
 * {@link #markFailed}, {@link #release}) is compare-and-set on {@code lease.owner} plus a live lease,
 * the same fence entities and cursors use (invariant 2). {@code false} means the lease was lost and the
 * caller must stop touching the delivery; a late {@code markSent} from a stale worker would otherwise
 * close out a delivery the new owner is mid-way through.
 */
public interface DeliveryRepository {

    /**
     * Insert, unless a delivery with the same non-null {@code dedupeKey} already exists — then return
     * that one untouched. Atomic on the unique index, so two concurrent enqueues cannot both win.
     */
    Delivery insertIfAbsent(Delivery delivery);

    Optional<Delivery> findById(String id);

    /**
     * Newest first. Any filter may be null.
     *
     * @param refId the producing record's id — a digest run's deliveries, for instance
     */
    List<Delivery> find(String channelId, String refId, DeliveryStatus status, int limit, int offset);

    /**
     * Atomically claim the oldest claimable delivery for one of {@code channelIds}: {@code PENDING},
     * backoff elapsed, and no live lease. One at a time on purpose — claiming a batch up front starts
     * every lease together, so a slow first send lets the last one's lease lapse while it is still queued.
     */
    Optional<Delivery> claimNext(Collection<String> channelIds, String owner, Duration lease, Instant now);

    /** {@code SENT}; resets attempts and drops the lease. Lease-fenced. */
    boolean markSent(String id, String owner, String providerMessageId, Instant sentAt);

    /** Stays {@code PENDING} with a failure recorded and a backoff. Lease-fenced. */
    boolean markRetry(String id, String owner, String error, int attempts, Instant nextAttemptAt);

    /** Dead-letter to {@code FAILED}. Lease-fenced. */
    boolean markFailed(String id, String owner, String error, int attempts);

    /** Drop the lease without recording an attempt — the channel became unusable after the claim. */
    boolean release(String id, String owner);

    /**
     * {@code FAILED} → {@code PENDING} with attempts reset, compare-and-set on the status.
     *
     * @return false if the delivery does not exist or is not {@code FAILED}
     */
    boolean requeue(String id, Instant at);

    /** Cascades from deleting a channel. */
    void deleteByChannel(String channelId);
}
