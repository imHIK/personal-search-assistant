package io.personalassistant.domain.model.enums;

/** Where one message is in the outbox. */
public enum DeliveryStatus {
    /** Waiting to be sent, or waiting out a retry backoff. A live lease means a worker holds it now. */
    PENDING,
    /** Accepted by the channel's transport. Terminal. */
    SENT,
    /**
     * Dead-lettered: a permanent failure, or {@code app.publishing.retry-limit} transient ones in a row.
     * Nothing reclaims it automatically; {@code POST /api/deliveries/{id}/retry} is the only way back.
     */
    FAILED
}
