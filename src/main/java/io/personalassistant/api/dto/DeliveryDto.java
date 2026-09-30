package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Delivery;
import java.time.Instant;

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
