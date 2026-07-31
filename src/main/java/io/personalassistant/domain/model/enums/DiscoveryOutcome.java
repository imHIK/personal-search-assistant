package io.personalassistant.domain.model.enums;

/**
 * Outcome of a single discovery run ({@code connector.discover}). {@code OK} means the source's
 * iterables were enumerated successfully; {@code FAILED} means discovery threw (bad credentials,
 * unreachable source, enumeration error) and the captured reason is on
 * {@link io.personalassistant.domain.model.DiscoveryStatus#lastError()}.
 */
public enum DiscoveryOutcome {
    OK,
    FAILED
}
