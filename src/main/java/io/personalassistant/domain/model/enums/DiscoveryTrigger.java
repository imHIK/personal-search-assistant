package io.personalassistant.domain.model.enums;

/**
 * What caused a discovery run. {@code ACTIVATION} is the one-off discover when a knowledge is added
 * ({@code DefaultKnowledgeService.add}); {@code RECONCILE} is the periodic re-discovery that picks
 * up new/removed iterables ({@code IterableDiscoveryScheduler} → {@code reconcileCursors}).
 */
public enum DiscoveryTrigger {
    ACTIVATION,
    RECONCILE
}
