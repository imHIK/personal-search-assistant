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

    /**
     * Record when the forward scheduler may next re-arm this knowledge's forward cursors. Written by
     * {@code ForwardCursorScheduler} after it arms (or defers) a knowledge, so the per-source cadence
     * survives restarts and is not re-triggered every tick. Leaves {@code updatedAt} untouched — this
     * is scheduler bookkeeping, not a user-visible config change.
     */
    void updateNextSyncDueAt(String id, java.time.Instant nextDueAt);

    void delete(String id);
}
