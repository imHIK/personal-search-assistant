package io.personalassistant.domain.model.enums;

/**
 * Lifecycle state of a {@link io.personalassistant.domain.model.Knowledge}.
 *
 * <ul>
 *   <li>{@code DRAFT} — created but not yet validated / discovered.</li>
 *   <li>{@code ACTIVE} — discovered, cursors created, eligible for ingestion.</li>
 *   <li>{@code PAUSED} — temporarily excluded from scheduling (no new work picked up).</li>
 *   <li>{@code ERROR} — verification or discovery failed; needs intervention.</li>
 *   <li>{@code DELETED} — soft-deleted; entities/chunks scheduled for teardown.</li>
 * </ul>
 */
public enum KnowledgeStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ERROR,
    DELETED
}
