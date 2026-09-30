package io.personalassistant.domain.model.enums;

/** DRAFT is persisted before activation; DELETED is a soft delete while teardown runs. */
public enum KnowledgeStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ERROR,
    DELETED
}
