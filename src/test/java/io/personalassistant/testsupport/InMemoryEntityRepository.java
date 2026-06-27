package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.storage.repository.EntityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link EntityRepository} mirroring the Mongo adapter's claim/upsert semantics. */
public class InMemoryEntityRepository implements EntityRepository {

    public final Map<String, Entity> store = new LinkedHashMap<>();

    @Override
    public Entity upsert(Entity entity) {
        Optional<Entity> existing = findByKnowledgeAndExternalId(entity.knowledgeId(), entity.externalId());
        String id = existing.map(Entity::id).orElse(entity.id());
        Instant createdAt = existing.map(Entity::createdAt).orElse(entity.createdAt());
        Entity stored = new Entity(id, entity.knowledgeId(), entity.iterableId(), entity.entityType(),
                entity.externalId(), entity.raw(), entity.content(), entity.metadata(), entity.checksum(),
                entity.status(), entity.needsReindex(), entity.index(), entity.lease(), entity.retry(),
                createdAt, entity.updatedAt());
        store.put(id, stored);
        return stored;
    }

    @Override
    public Optional<Entity> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Entity> findByKnowledgeAndExternalId(String knowledgeId, String externalId) {
        return store.values().stream()
                .filter(e -> e.knowledgeId().equals(knowledgeId) && e.externalId().equals(externalId))
                .findFirst();
    }

    @Override
    public List<Entity> claimForIndexing(int limit, String owner, Duration lease) {
        return claimForIndexing(null, limit, owner, lease);
    }

    @Override
    public List<Entity> claimForIndexing(String knowledgeId, int limit, String owner, Duration lease) {
        Instant now = Instant.now();
        List<Entity> claimed = new ArrayList<>();
        for (Entity e : new ArrayList<>(store.values())) {
            if (claimed.size() >= limit) {
                break;
            }
            if ((knowledgeId == null || e.knowledgeId().equals(knowledgeId)) && indexable(e, now)) {
                Entity leased = e.withStatus(EntityStatus.INDEXING, now)
                        .withLease(new Entity.Lease(owner, now.plus(lease)));
                store.put(e.id(), leased);
                claimed.add(leased);
            }
        }
        return claimed;
    }

    @Override
    public List<String> distinctPendingKnowledgeIds(int limit) {
        Instant now = Instant.now();
        List<String> ids = new ArrayList<>();
        for (Entity e : store.values()) {
            if (indexable(e, now) && !ids.contains(e.knowledgeId()) && ids.size() < limit) {
                ids.add(e.knowledgeId());
            }
        }
        return ids;
    }

    @Override
    public List<Entity> claimForDeletion(int limit, String owner, Duration lease) {
        Instant now = Instant.now();
        List<Entity> claimed = new ArrayList<>();
        for (Entity e : new ArrayList<>(store.values())) {
            if (claimed.size() >= limit) {
                break;
            }
            boolean leaseFree = e.lease() == null || !e.lease().isLiveAt(now);
            if (e.status() == EntityStatus.DELETED && e.needsReindex() && leaseFree) {
                Entity leased = e.withLease(new Entity.Lease(owner, now.plus(lease)));
                store.put(e.id(), leased);
                claimed.add(leased);
            }
        }
        return claimed;
    }

    @Override
    public void markIndexed(String id, int chunkCount, String embeddingModel, Instant indexedAt) {
        mutate(id, e -> rebuild(e, EntityStatus.INDEXED, false,
                new Entity.IndexInfo(chunkCount, embeddingModel, indexedAt, null), null, e.retry()));
    }

    @Override
    public void markDeletionComplete(String id, Instant cleanedAt) {
        mutate(id, e -> rebuild(e, e.status(), false,
                new Entity.IndexInfo(0, e.index().embeddingModel(), cleanedAt, e.index().error()), null, e.retry()));
    }

    @Override
    public void markFailed(String id, EntityStatus restingStatus, String error, int retryCount, Instant nextAttemptAt) {
        mutate(id, e -> rebuild(e, restingStatus, e.needsReindex(),
                new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(), e.index().indexedAt(), error),
                null, new Entity.Retry(retryCount, nextAttemptAt)));
    }

    @Override
    public void flagNeedsReindex(String id) {
        mutate(id, e -> rebuild(e, e.status(), true, e.index(), e.lease(), e.retry()));
    }

    @Override
    public void markDeleted(String id, Instant updatedAt) {
        mutate(id, e -> rebuild(e, EntityStatus.DELETED, true, e.index(), e.lease(), e.retry()));
    }

    @Override
    public List<Entity> findByStatus(EntityStatus status, int limit) {
        return store.values().stream().filter(e -> e.status() == status).limit(limit).toList();
    }

    @Override
    public long countByKnowledgeAndStatus(String knowledgeId, EntityStatus status) {
        return store.values().stream()
                .filter(e -> e.knowledgeId().equals(knowledgeId) && e.status() == status).count();
    }

    @Override
    public long countByKnowledge(String knowledgeId) {
        return store.values().stream().filter(e -> e.knowledgeId().equals(knowledgeId)).count();
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        store.values().removeIf(e -> e.knowledgeId().equals(knowledgeId));
    }

    @Override
    public void deleteByKnowledgeAndIterable(String knowledgeId, String iterableId) {
        store.values().removeIf(e -> e.knowledgeId().equals(knowledgeId)
                && e.iterableId().equals(iterableId));
    }

    private boolean indexable(Entity e, Instant now) {
        boolean backoffReady = e.retry() == null || e.retry().nextAttemptAt() == null
                || !e.retry().nextAttemptAt().isAfter(now);
        if (!backoffReady) {
            return false;
        }
        if (e.status() == EntityStatus.INGESTED) {
            return true;
        }
        if (e.needsReindex() && e.status() != EntityStatus.DELETED && e.status() != EntityStatus.INDEXING) {
            return true;
        }
        return e.status() == EntityStatus.INDEXING && (e.lease() == null || !e.lease().isLiveAt(now));
    }

    private void mutate(String id, java.util.function.UnaryOperator<Entity> op) {
        Entity e = store.get(id);
        if (e != null) {
            store.put(id, op.apply(e));
        }
    }

    private static Entity rebuild(Entity e, EntityStatus status, boolean needsReindex,
                                  Entity.IndexInfo index, Entity.Lease lease, Entity.Retry retry) {
        return new Entity(e.id(), e.knowledgeId(), e.iterableId(), e.entityType(), e.externalId(),
                e.raw(), e.content(), e.metadata(), e.checksum(), status, needsReindex, index, lease,
                retry, e.createdAt(), Instant.now());
    }
}
