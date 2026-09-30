package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository {

    /** Insert or replace by id. */
    Knowledge save(Knowledge knowledge);

    Optional<Knowledge> findById(String id);

    List<Knowledge> findAll();

    List<Knowledge> findByStatus(KnowledgeStatus status);

    List<Knowledge> findByConnectionId(String connectionId);

    void updateStatus(String id, KnowledgeStatus status);

    void markError(String id, String lastError);

    /** Leaves updatedAt alone: scheduler bookkeeping, not a config change. */
    void updateNextSyncDueAt(String id, java.time.Instant nextDueAt);

    void delete(String id);
}
