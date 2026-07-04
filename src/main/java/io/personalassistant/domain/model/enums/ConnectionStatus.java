package io.personalassistant.domain.model.enums;

/**
 * Lifecycle state of a {@link io.personalassistant.domain.model.Connection} — a reusable,
 * account-level set of credentials for a connector.
 */
public enum ConnectionStatus {
    /** Credentials verified and usable; knowledges may bind to it. */
    ACTIVE,
    /** Last credential check failed (e.g. revoked token). Bound knowledges surface the error. */
    ERROR,
    /** Manually disabled by the user; excluded from default resolution and new bindings. */
    DISABLED
}
