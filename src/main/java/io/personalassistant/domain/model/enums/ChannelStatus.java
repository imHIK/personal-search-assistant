package io.personalassistant.domain.model.enums;

/**
 * Whether a channel can currently deliver.
 *
 * <p>Its own enum rather than a reuse of {@link ConnectionStatus}, which it resembles: publishing
 * imports nothing from the connector side, so the two can diverge without either noticing.
 */
public enum ChannelStatus {
    /** Last check or send succeeded (or it has never been checked). Deliveries are claimed. */
    ACTIVE,
    /**
     * A permanent failure — rejected credentials, an address the server refuses. Deliveries for it stay
     * {@code PENDING} without spending attempts until a successful test flips it back.
     */
    ERROR
}
