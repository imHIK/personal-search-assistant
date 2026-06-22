package io.personalassistant.storage.mongo;

import io.personalassistant.domain.model.Source;
import io.personalassistant.storage.repository.SourceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB adapter for {@link SourceRepository}. Stub for the wiring pass — satisfies the
 * bean graph so the app boots. Implement against the {@code sources} collection next.
 */
@ApplicationScoped
public class MongoSourceRepository implements SourceRepository {

    @Override
    public Source save(Source source) {
        throw new UnsupportedOperationException("TODO: persist to Mongo 'sources'");
    }

    @Override
    public Optional<Source> findById(String id) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public List<Source> findAll() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void updateSyncState(String id, Source.SyncState sync) {
        throw new UnsupportedOperationException("TODO: persist sync watermark");
    }

    @Override
    public void delete(String id) {
        throw new UnsupportedOperationException("TODO");
    }
}
