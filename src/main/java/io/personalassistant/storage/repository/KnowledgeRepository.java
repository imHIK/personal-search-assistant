package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import java.util.List;
import java.util.Optional;

/** Persistence port for the {@code knowledge} collection (replaces {@code SourceRepository}). */
public interface KnowledgeRepository {

    /** Insert or replace by {@code id}. */
    Knowledge save(Knowledge knowledge);

    Optional<Knowledge> findById(String id);

    List<Knowledge> findAll();

    /** Used by the scheduler to find knowledge eligible for forward re-arming. */
    List<Knowledge> findByStatus(KnowledgeStatus status);

    void updateStatus(String id, KnowledgeStatus status);

    /**
     * Flip a knowledge to {@code ERROR} and record why (a verification/discovery/activation
     * failure), so the failure is inspectable from the stored record rather than only the log.
     */
    void markError(String id, String lastError);

    void delete(String id);
}
