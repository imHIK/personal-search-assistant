package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.time.Instant;

/**
 * One message on its way to one channel: an outbox row a worker sends later. At-least-once: a worker crashing
 * after the transport accepted the message, before markSent lands, lets the next worker send a second copy.
 *
 * @param dedupeKey a second enqueue with the same key returns the first delivery; null never collapses
 * @param message a snapshot, so a retry sends what was queued
 * @param attempts consecutive failures; success and a manual retry reset it
 * @param nextAttemptAt null means now
 * @param lease every terminal write is fenced on it
 */
public record Delivery(
        String id,
        String channelId,
        Origin origin,
        String dedupeKey,
        PublishMessage message,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Lease lease,
        String lastError,
        String providerMessageId,
        Instant createdAt,
        Instant sentAt) {

    public Delivery {
        status = status == null ? DeliveryStatus.PENDING : status;
        attempts = Math.max(attempts, 0);
        dedupeKey = dedupeKey == null || dedupeKey.isBlank() ? null : dedupeKey;
        origin = origin == null ? Origin.manual() : origin;
    }

    public static Delivery pending(String id, String channelId, Origin origin, String dedupeKey,
                                   PublishMessage message, Instant now) {
        return new Delivery(id, channelId, origin, dedupeKey, message, DeliveryStatus.PENDING, 0, null,
                null, null, null, now, null);
    }

    /** {@code kind} is a string, so a new producer needs no schema change. */
    public record Origin(String kind, String refId) {

        public static final String MANUAL = "MANUAL";

        /** {@code refId} is the run id. */
        public static final String DIGEST_RUN = "DIGEST_RUN";

        public Origin {
            kind = kind == null || kind.isBlank() ? MANUAL : kind;
        }

        public static Origin manual() {
            return new Origin(MANUAL, null);
        }
    }

    public record Lease(String owner, Instant expiresAt) {
    }
}
