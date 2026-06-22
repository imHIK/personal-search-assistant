package io.personalassistant.storage.mongo;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.storage.repository.ChunkRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * MongoDB adapter for {@link ChunkRepository}. Stub for the wiring pass. Implement against
 * the {@code chunks} collection next (replace-for-document should be atomic).
 */
@ApplicationScoped
public class MongoChunkRepository implements ChunkRepository {

    @Override
    public void replaceForDocument(String documentId, List<Chunk> chunks) {
        throw new UnsupportedOperationException("TODO: delete+insert chunks for document");
    }

    @Override
    public List<Chunk> findByDocument(String documentId) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void deleteByDocument(String documentId) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void deleteBySource(String sourceId) {
        throw new UnsupportedOperationException("TODO");
    }
}
