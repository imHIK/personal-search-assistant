package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.storage.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link DeliveryRepository} for tests, with the Mongo adapter's lease fence: post-claim writes
 * apply only while the caller holds a live lease.
 */
public class InMemoryDeliveryRepository implements DeliveryRepository {

    public final Map<String, Delivery> store = new LinkedHashMap<>();

    @Override
    public Delivery insertIfAbsent(Delivery delivery) {
        if (delivery.dedupeKey() != null) {
            Optional<Delivery> existing = store.values().stream()
                    .filter(d -> delivery.dedupeKey().equals(d.dedupeKey())).findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        store.put(delivery.id(), delivery);
        return delivery;
    }

    @Override
    public Optional<Delivery> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Delivery> find(String channelId, String refId, DeliveryStatus status, int limit, int offset) {
        return store.values().stream()
                .filter(d -> channelId == null || channelId.equals(d.channelId()))
                .filter(d -> refId == null || refId.equals(d.origin().refId()))
                .filter(d -> status == null || status == d.status())
                .sorted(Comparator.comparing(Delivery::createdAt).reversed())
                .skip(Math.max(offset, 0))
                .limit(Math.max(limit, 1))
                .toList();
    }

    @Override
    public Optional<Delivery> claimNext(Collection<String> channelIds, String owner, Duration lease,
                                        Instant now) {
        Optional<Delivery> next = store.values().stream()
                .filter(d -> d.status() == DeliveryStatus.PENDING)
                .filter(d -> channelIds.contains(d.channelId()))
                .filter(d -> d.nextAttemptAt() == null || !d.nextAttemptAt().isAfter(now))
                .filter(d -> d.lease() == null || d.lease().expiresAt().isBefore(now))
                .min(Comparator.comparing(Delivery::createdAt));
        next.ifPresent(d -> store.put(d.id(), with(d, d.status(), d.attempts(), d.nextAttemptAt(),
                new Delivery.Lease(owner, now.plus(lease)), d.lastError(), d.providerMessageId(), d.sentAt())));
        return next.map(d -> store.get(d.id()));
    }

    @Override
    public boolean markSent(String id, String owner, String providerMessageId, Instant sentAt) {
        return owned(id, owner, d -> with(d, DeliveryStatus.SENT, 0, null, null, null, providerMessageId,
                sentAt));
    }

    @Override
    public boolean markRetry(String id, String owner, String error, int attempts, Instant nextAttemptAt) {
        return owned(id, owner, d -> with(d, d.status(), attempts, nextAttemptAt, null, error,
                d.providerMessageId(), d.sentAt()));
    }

    @Override
    public boolean markFailed(String id, String owner, String error, int attempts) {
        return owned(id, owner, d -> with(d, DeliveryStatus.FAILED, attempts, null, null, error,
                d.providerMessageId(), d.sentAt()));
    }

    @Override
    public boolean release(String id, String owner) {
        return owned(id, owner, d -> with(d, d.status(), d.attempts(), d.nextAttemptAt(), null, d.lastError(),
                d.providerMessageId(), d.sentAt()));
    }

    @Override
    public boolean requeue(String id, Instant at) {
        Delivery d = store.get(id);
        if (d == null || d.status() != DeliveryStatus.FAILED) {
            return false;
        }
        store.put(id, with(d, DeliveryStatus.PENDING, 0, null, null, d.lastError(), d.providerMessageId(),
                d.sentAt()));
        return true;
    }

    @Override
    public void deleteByChannel(String channelId) {
        store.values().removeIf(d -> channelId.equals(d.channelId()));
    }

    /** Simulate another worker taking over the delivery, as after the caller's lease expired. */
    public void steal(String id, String newOwner) {
        Delivery d = store.get(id);
        store.put(id, with(d, d.status(), d.attempts(), d.nextAttemptAt(),
                new Delivery.Lease(newOwner, Instant.now().plusSeconds(600)), d.lastError(),
                d.providerMessageId(), d.sentAt()));
    }

    private boolean owned(String id, String owner, java.util.function.UnaryOperator<Delivery> change) {
        Delivery d = store.get(id);
        if (d == null || d.lease() == null || !owner.equals(d.lease().owner())
                || !d.lease().expiresAt().isAfter(Instant.now())) {
            return false;
        }
        store.put(id, change.apply(d));
        return true;
    }

    private static Delivery with(Delivery d, DeliveryStatus status, int attempts, Instant nextAttemptAt,
                                 Delivery.Lease lease, String lastError, String providerMessageId,
                                 Instant sentAt) {
        return new Delivery(d.id(), d.channelId(), d.origin(), d.dedupeKey(), d.message(), status, attempts,
                nextAttemptAt, lease, lastError, providerMessageId, d.createdAt(), sentAt);
    }
}
