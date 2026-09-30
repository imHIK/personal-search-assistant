package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import java.util.List;
import java.util.Optional;

public interface DiscoveryStatusRepository {

    /**
     * Upserts the (knowledgeId, direction) document, overwriting the latest fields and bumping the counters.
     */
    void record(DiscoveryStatus.Run run);

    Optional<DiscoveryStatus> find(String knowledgeId, CursorDirection direction);

    List<DiscoveryStatus> findByKnowledge(String knowledgeId);

    void deleteByKnowledge(String knowledgeId);
}
