package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@code discovery} collection: one {@link DiscoveryStatus} per
 * {@code (knowledgeId, direction)} capturing that grabber's latest {@code connector.discover} outcome.
 */
public interface DiscoveryStatusRepository {

    /**
     * Fold one discovery run into the stored status for its {@code (knowledgeId, direction)} —
     * upserting the document, overwriting the "latest" fields and bumping the run/failure counters.
     * Each call increments {@code runCount}.
     */
    void record(DiscoveryStatus.Run run);

    Optional<DiscoveryStatus> find(String knowledgeId, CursorDirection direction);

    List<DiscoveryStatus> findByKnowledge(String knowledgeId);

    void deleteByKnowledge(String knowledgeId);
}
