package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.storage.repository.KnowledgeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Simple in-memory {@link KnowledgeRepository} for unit tests (no Mongo). */
public class InMemoryKnowledgeRepository implements KnowledgeRepository {

    public final Map<String, Knowledge> store = new LinkedHashMap<>();

    @Override
    public Knowledge save(Knowledge knowledge) {
        store.put(knowledge.id(), knowledge);
        return knowledge;
    }

    @Override
    public Optional<Knowledge> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Knowledge> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Knowledge> findByStatus(KnowledgeStatus status) {
        return store.values().stream().filter(k -> k.status() == status).toList();
    }

    @Override
    public List<Knowledge> findByConnectionId(String connectionId) {
        return store.values().stream()
                .filter(k -> connectionId.equals(k.connectorDetails().connectionId()))
                .toList();
    }

    @Override
    public void updateStatus(String id, KnowledgeStatus status) {
        Knowledge k = store.get(id);
        if (k != null) {
            store.put(id, new Knowledge(k.id(), k.name(), k.connectorDetails(), k.inputs(), k.config(),
                    k.anchor(), k.nextSyncDueAt(), status, k.lastError(), k.stats(), k.createdAt(), Instant.now(),
                    k.syncGeneration()));
        }
    }

    @Override
    public void markError(String id, String lastError) {
        Knowledge k = store.get(id);
        if (k != null) {
            store.put(id, new Knowledge(k.id(), k.name(), k.connectorDetails(), k.inputs(), k.config(),
                    k.anchor(), k.nextSyncDueAt(), KnowledgeStatus.ERROR, lastError, k.stats(), k.createdAt(), Instant.now(),
                    k.syncGeneration()));
        }
    }

    @Override
    public void updateNextSyncDueAt(String id, Instant nextDueAt) {
        Knowledge k = store.get(id);
        if (k != null) {
            store.put(id, k.withNextSyncDueAt(nextDueAt));
        }
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
