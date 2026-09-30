package io.personalassistant.domain.model.enums;

public enum ChannelStatus {
    ACTIVE,
    /** Deliveries stay PENDING without spending attempts until a successful test flips it back. */
    ERROR
}
