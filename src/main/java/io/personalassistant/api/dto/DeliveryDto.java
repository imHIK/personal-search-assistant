package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Delivery;
import java.time.Instant;

/**
 * Wire shape for one delivery in the publishing outbox.
 *
 * @param origin            what queued it: {@code {kind: "MANUAL", refId: null}}
 * @param dedupeKey         the idempotency key it was queued with, or null
 * @param message           exactly what was (or will be) sent
 * @param status            {@code PENDING} / {@code SENT} / {@code FAILED}
 * @param attempts          consecutive failed attempts
 * @param nextAttemptAt     earliest next attempt while backing off, or null
 * @param leasedUntil       when the worker currently sending it gives up its claim, or null when idle
 * @param lastError         why the most recent attempt failed, or null
 * @param providerMessageId the transport's id for the sent message, or null
 */
public record DeliveryDto(
        String id,
        String channelId,
        Origin origin,
        String dedupeKey,
        PublishMessageDto message,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant leasedUntil,
        String lastError,
        String providerMessageId,
        Instant createdAt,
        Instant sentAt) {

    public record Origin(String kind, String refId) {
    }

    public static DeliveryDto from(Delivery d) {
        return new DeliveryDto(d.id(), d.channelId(), new Origin(d.origin().kind(), d.origin().refId()),
                d.dedupeKey(), PublishMessageDto.from(d.message()), d.status().name(), d.attempts(),
                d.nextAttemptAt(), d.lease() == null ? null : d.lease().expiresAt(), d.lastError(),
                d.providerMessageId(), d.createdAt(), d.sentAt());
    }
}
