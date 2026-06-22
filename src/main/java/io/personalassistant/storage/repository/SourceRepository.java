package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Source;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@code sources}. */
public interface SourceRepository {

    Source save(Source source);

    Optional<Source> findById(String id);

    List<Source> findAll();

    /** Persist the sync watermark after a run. */
    void updateSyncState(String id, Source.SyncState sync);

    void delete(String id);
}
