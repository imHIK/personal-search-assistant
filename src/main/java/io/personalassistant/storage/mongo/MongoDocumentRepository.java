package io.personalassistant.storage.mongo;

import io.personalassistant.domain.model.Document;
import io.personalassistant.domain.model.enums.IndexStatus;
import io.personalassistant.storage.repository.DocumentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB adapter for {@link DocumentRepository}. Stub for the design pass.
 *
 * <p>Implementation plan (next pass):
 * <ul>
 *   <li>Inject the Quarkus Mongo client / Panache for collection {@code documents}.</li>
 *   <li>{@code upsert}: filter on {@code (sourceId, externalId)}, replace-with-upsert.</li>
 *   <li>Ensure indexes from docs/mongodb-schema.md on startup.</li>
 *   <li>Map between the {@link Document} record and BSON.</li>
 * </ul>
 */
@ApplicationScoped
public class MongoDocumentRepository implements DocumentRepository {

    @Override
    public Document upsert(Document document) {
        throw new UnsupportedOperationException("TODO: Mongo upsert by (sourceId, externalId)");
    }

    @Override
    public Optional<Document> findById(String id) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Optional<Document> findBySourceAndExternalId(String sourceId, String externalId) {
        throw new UnsupportedOperationException("TODO: change-detection lookup");
    }

    @Override
    public List<Document> findByStatus(IndexStatus status, int limit) {
        throw new UnsupportedOperationException("TODO: work-queue query");
    }

    @Override
    public void updateStatus(String id, IndexStatus status, String error) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void delete(String id) {
        throw new UnsupportedOperationException("TODO");
    }
}
