package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.storage.repository.EntityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
        // Mirrors the Mongo adapter's field ownership: the indexer's chunkCount/model/indexedAt
        // survive (they describe what is in the index right now), index.error is cleared, and the
        // work queue is reset with the lease dropped so an in-flight indexer is fenced out.
        Entity.IndexInfo priorIndex = existing.map(Entity::index).orElse(Entity.IndexInfo.empty());
        Entity.IndexInfo index = new Entity.IndexInfo(priorIndex.chunkCount(),
                priorIndex.embeddingModel(), priorIndex.indexedAt(), null);
        Entity stored = new Entity(id, entity.knowledgeId(), entity.iterableId(), entity.entityType(),
                entity.externalId(), entity.raw(), entity.content(), entity.metadata(), entity.checksum(),
                EntityStatus.INGESTED, false, index, null, Entity.Retry.zero(),
                createdAt, entity.updatedAt(), entity.lastSeenGeneration());
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
    public boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt) {
        return fenced(id, owner, e -> rebuild(e, EntityStatus.INDEXED, false,
                new Entity.IndexInfo(chunkCount, embeddingModel, indexedAt, null), null, Entity.Retry.zero()));
    }

    @Override
    public boolean markDeletionComplete(String id, String owner, Instant cleanedAt) {
        return fenced(id, owner, e -> rebuild(e, e.status(), false,
                new Entity.IndexInfo(0, e.index().embeddingModel(), cleanedAt, e.index().error()),
                null, Entity.Retry.zero()));
    }

    @Override
    public boolean markFailed(String id, String owner, EntityStatus restingStatus, String error,
                              int retryCount, Instant nextAttemptAt) {
        // A terminal FAILED also clears needsReindex — mirrors the Mongo adapter's dead-letter exit.
        boolean stillFlagged = restingStatus != EntityStatus.FAILED;
        return fenced(id, owner, e -> rebuild(e, restingStatus, stillFlagged && e.needsReindex(),
                new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(), e.index().indexedAt(), error),
                null, new Entity.Retry(retryCount, nextAttemptAt)));
    }

    @Override
    public void flagNeedsReindex(String id) {
        mutate(id, e -> {
            // Revive a dead-lettered entity with a fresh retry budget; lease deliberately untouched.
            EntityStatus status = e.status() == EntityStatus.FAILED ? EntityStatus.INGESTED : e.status();
            String error = e.status() == EntityStatus.FAILED ? null : e.index().error();
            return rebuild(e, status, true,
                    new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(),
                            e.index().indexedAt(), error),
                    e.lease(), Entity.Retry.zero());
        });
    }

    @Override
    public void stampLastSeen(String id, long generation) {
        mutate(id, e -> e.withLastSeenGeneration(generation));
    }

    @Override
    public int retryFailedByKnowledge(String knowledgeId) {
        int revived = 0;
        for (Entity e : new ArrayList<>(store.values())) {
            if (e.knowledgeId().equals(knowledgeId) && e.status() == EntityStatus.FAILED) {
                store.put(e.id(), rebuild(e, EntityStatus.INGESTED, e.needsReindex(),
                        new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(),
                                e.index().indexedAt(), null),
                        null, Entity.Retry.zero()));
                revived++;
            }
        }
        return revived;
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
    public List<EntitySummary> findByKnowledge(String knowledgeId, EntityStatus status, int limit, int offset) {
        // Mirrors the Mongo adapter's ordering exactly: updatedAt descending, id ascending as tiebreak.
        return store.values().stream()
                .filter(e -> e.knowledgeId().equals(knowledgeId) && (status == null || e.status() == status))
                .sorted(Comparator.comparing(Entity::updatedAt).reversed().thenComparing(Entity::id))
                .skip(offset)
                .limit(limit)
                .map(InMemoryEntityRepository::toSummary)
                .toList();
    }

    private static EntitySummary toSummary(Entity e) {
        return new EntitySummary(e.id(), e.knowledgeId(), e.externalId(), e.entityType(), e.status(),
                e.title(), e.uri(), e.checksum(),
                e.index() == null ? Entity.IndexInfo.empty() : e.index(),
                e.retry() == null ? 0 : e.retry().count(),
                e.needsReindex(), e.updatedAt());
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
        // FAILED excluded: dead-letter, only flagNeedsReindex brings it back. Mirrors the Mongo filter.
        if (e.needsReindex() && e.status() != EntityStatus.DELETED && e.status() != EntityStatus.INDEXING
                && e.status() != EntityStatus.FAILED) {
            return true;
        }
        return e.status() == EntityStatus.INDEXING && (e.lease() == null || !e.lease().isLiveAt(now));
    }

    /**
     * Put an entity exactly as given, bypassing the work-queue reset {@link #upsert} applies. For
     * tests that want a <em>state</em> as a fixture rather than to exercise ingestion's contract.
     */
    public void seed(Entity entity) {
        store.put(entity.id(), entity);
    }

    /**
     * Fixture helper: drive an entity to {@code INDEXED} without going through the lease protocol.
     * Callers that genuinely test the protocol should claim first and pass the real owner.
     */
    public void seedIndexed(String id, int chunkCount, String embeddingModel, Instant indexedAt) {
        mutate(id, e -> rebuild(e, EntityStatus.INDEXED, false,
                new Entity.IndexInfo(chunkCount, embeddingModel, indexedAt, null), null, Entity.Retry.zero()));
    }

    /** Fixture counterpart to {@link #seedIndexed} for a failure resting state. */
    public void seedFailed(String id, EntityStatus restingStatus, String error, int retryCount) {
        mutate(id, e -> rebuild(e, restingStatus, restingStatus != EntityStatus.FAILED && e.needsReindex(),
                new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(),
                        e.index().indexedAt(), error),
                null, new Entity.Retry(retryCount, null)));
    }

    private void mutate(String id, java.util.function.UnaryOperator<Entity> op) {
        Entity e = store.get(id);
        if (e != null) {
            store.put(id, op.apply(e));
        }
    }

    /** Lease fence mirroring the Mongo adapter's {@code ownedBy}: a stale worker's write is a no-op. */
    private boolean fenced(String id, String owner, java.util.function.UnaryOperator<Entity> op) {
        Entity e = store.get(id);
        if (e == null || e.lease() == null || !owner.equals(e.lease().owner())
                || !e.lease().isLiveAt(Instant.now())) {
            return false;
        }
        store.put(id, op.apply(e));
        return true;
    }

    private static Entity rebuild(Entity e, EntityStatus status, boolean needsReindex,
                                  Entity.IndexInfo index, Entity.Lease lease, Entity.Retry retry) {
        return new Entity(e.id(), e.knowledgeId(), e.iterableId(), e.entityType(), e.externalId(),
                e.raw(), e.content(), e.metadata(), e.checksum(), status, needsReindex, index, lease,
                retry, e.createdAt(), Instant.now(), e.lastSeenGeneration());
    }
}
