package io.personalassistant.domain.model.enums;

public enum DeliveryStatus {
    /** A live lease means a worker holds it now. */
    PENDING,
    /** Terminal. */
    SENT,
    /** Dead-letter. Nothing reclaims it; {@code POST /api/deliveries/{id}/retry} is the only way back. */
    FAILED
}
