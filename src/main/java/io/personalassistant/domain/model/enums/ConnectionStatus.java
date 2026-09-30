package io.personalassistant.domain.model.enums;

public enum ConnectionStatus {
    ACTIVE,
    ERROR,
    /** Excluded from default resolution and new bindings. */
    DISABLED
}
