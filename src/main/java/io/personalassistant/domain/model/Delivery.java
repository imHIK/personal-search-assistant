package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.DeliveryStatus;
import java.time.Instant;

/**
 * One message on its way to one channel — a row in the publishing outbox.
 *
 * <p>Enqueueing and sending are separate on purpose. Whoever wants something published (a person
 * pressing a button today, a digest run later) only writes this row and returns; a worker sends it.
 * So a slow or unreachable mail server never fails or stalls the caller, a transient failure is a
 * retry rather than a lost message, and "send it again" is a status flip on a row that already holds
 * exactly what was meant to go out.
 *
 * <p>Delivery is <strong>at-least-once</strong>. A worker that crashes after the transport accepted the
 * message but before {@code markSent} lands leaves the row claimable again when its lease expires, and
 * the next worker sends a second copy. See {@code docs/limitations.md} L13.
 *
 * @param id                stable id, {@code dlv_...}
 * @param channelId         where it goes
 * @param origin            what asked for it
 * @param dedupeKey         makes enqueueing idempotent: a second enqueue with the same key returns the
 *                          first delivery instead of adding another. Null for sends that should never
 *                          collapse, such as a manual publish
 * @param message           a snapshot, not a reference. A retry sends what was originally queued even if
 *                          whatever produced it has since changed, and the history shows what went out
 * @param status            outbox state
 * @param attempts          <em>consecutive</em> failed attempts; success and a manual retry reset it
 * @param nextAttemptAt     earliest time a worker may claim it again; null means now
 * @param lease             the worker currently sending it, or null. Every terminal write is fenced on it
 * @param lastError         why the most recent attempt failed, or null
 * @param providerMessageId the transport's id for the sent message (an SMTP Message-ID), or null
 * @param createdAt         when it was queued
 * @param sentAt            when the transport accepted it, or null
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

    /** A freshly queued delivery. */
    public static Delivery pending(String id, String channelId, Origin origin, String dedupeKey,
                                   PublishMessage message, Instant now) {
        return new Delivery(id, channelId, origin, dedupeKey, message, DeliveryStatus.PENDING, 0, null,
                null, null, null, now, null);
    }

    /**
     * What asked for a delivery. {@code kind} is a string rather than an enum so a new producer does
     * not need a schema change to identify itself.
     *
     * @param kind  {@link #MANUAL} or {@link #DIGEST_RUN}
     * @param refId the producing record's id (a run id), or null
     */
    public record Origin(String kind, String refId) {

        public static final String MANUAL = "MANUAL";

        /** A digest run published to its channels; {@code refId} is the run id. */
        public static final String DIGEST_RUN = "DIGEST_RUN";

        public Origin {
            kind = kind == null || kind.isBlank() ? MANUAL : kind;
        }

        public static Origin manual() {
            return new Origin(MANUAL, null);
        }
    }

    /**
     * A worker's claim on a delivery.
     *
     * @param owner     worker id
     * @param expiresAt after this, another worker may claim it
     */
    public record Lease(String owner, Instant expiresAt) {
    }
}
