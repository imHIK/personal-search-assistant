package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Chunk;
import java.util.List;

/** Persistence port for {@code chunks} (the canonical copy; OpenSearch is the query path). */
public interface ChunkRepository {

    /** Replace all chunks for a document atomically (idempotent re-chunking). */
    void replaceForDocument(String documentId, List<Chunk> chunks);

    List<Chunk> findByDocument(String documentId);

    void deleteByDocument(String documentId);

    void deleteBySource(String sourceId);
}
