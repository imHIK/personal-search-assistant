package io.personalassistant.domain.model.enums;

/**
 * Coarse classification of an ingested {@link io.personalassistant.domain.model.Entity},
 * used to drive transform decisions (e.g. file extraction vs. inline text) and for display.
 */
public enum EntityType {
    FILE,
    MESSAGE,
    EMAIL,
    PAGE,
    OTHER
}
