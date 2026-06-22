package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Document;
import io.personalassistant.domain.model.enums.IndexStatus;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@code documents}. Implemented by the Mongo adapter today; any
 * other store later only needs to satisfy this interface.
 */
public interface DocumentRepository {

    /** Insert or update by natural key {@code (sourceId, externalId)}. */
    Document upsert(Document document);

    Optional<Document> findById(String id);

    /** Used for change detection during sync. */
    Optional<Document> findBySourceAndExternalId(String sourceId, String externalId);

    /** Work-queue query: documents in a given pipeline state. */
    List<Document> findByStatus(IndexStatus status, int limit);

    void updateStatus(String id, IndexStatus status, String error);

    void delete(String id);
}
