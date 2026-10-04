package io.personalassistant.testsupport;

import io.personalassistant.domain.model.EnrichmentOutcome;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.EntityQuery;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.FacetValue;
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

/** Mirrors the Mongo adapter's claim and upsert semantics. */
public class InMemoryEntityRepository implements EntityRepository {

    public final Map<String, Entity> store = new LinkedHashMap<>();

    @Override
    public Entity upsert(Entity entity) {
        Optional<Entity> existing = findByKnowledgeAndExternalId(entity.knowledgeId(), entity.externalId());
        String id = existing.map(Entity::id).orElse(entity.id());
        Instant createdAt = existing.map(Entity::createdAt).orElse(entity.createdAt());
        // Field ownership as in Mongo: the indexer's fields survive, index.error is cleared, and the lease is
        // dropped so an in-flight indexer is fenced out.
        Entity.IndexInfo priorIndex = existing.map(Entity::index).orElse(Entity.IndexInfo.empty());
        Entity.IndexInfo index = new Entity.IndexInfo(priorIndex.chunkCount(),
                priorIndex.embeddingModel(), priorIndex.indexedAt(), null);
        Entity stored = new Entity(id, entity.knowledgeId(), entity.iterableId(), entity.entityType(),
                entity.externalId(), entity.raw(), entity.content(), entity.metadata(), entity.checksum(),
                EntityStatus.INGESTED, false, false, index, null, Entity.Retry.zero(),
                createdAt, entity.updatedAt(), entity.expiresAt(), entity.lastSeenGeneration(),
                existing.map(Entity::enriched).orElse(Map.of()),
                existing.map(Entity::enrichment).orElse(null),
                existing.map(Entity::custom).orElse(Map.of()));
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
    public boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt,
                               EnrichmentOutcome enrichment) {
        return fenced(id, owner, e -> withEnrichment(rebuild(e, EntityStatus.INDEXED, false,
                new Entity.IndexInfo(chunkCount, embeddingModel, indexedAt, null), null, Entity.Retry.zero()),
                enrichment));
    }

    private static Entity withEnrichment(Entity e, EnrichmentOutcome outcome) {
        Map<String, Object> values = switch (outcome.kind()) {
            case KEEP, ERROR -> e.enriched();
            case CLEAR -> Map.of();
            case SET -> outcome.values();
        };
        Entity.Enrichment stamp = switch (outcome.kind()) {
            case KEEP -> e.enrichment();
            case CLEAR -> null;
            case SET, ERROR -> outcome.stamp();
        };
        return new Entity(e.id(), e.knowledgeId(), e.iterableId(), e.entityType(), e.externalId(),
                e.raw(), e.content(), e.metadata(), e.checksum(), e.status(), e.needsReindex(),
                e.needsRefetch(), e.index(), e.lease(), e.retry(), e.createdAt(), e.updatedAt(),
                e.expiresAt(), e.lastSeenGeneration(), values, stamp, e.custom());
    }

    @Override
    public boolean markDeletionComplete(String id, String owner, Instant cleanedAt) {
        return fenced(id, owner, e -> rebuild(e, e.status(), false,
                new Entity.IndexInfo(0, e.index().embeddingModel(), cleanedAt, e.index().error()),
                null, Entity.Retry.zero()));
    }

    @Override
    public boolean markFailed(String id, String owner, EntityStatus restingStatus, String error,
                              int retryCount, Instant nextAttemptAt, EnrichmentOutcome enrichment) {
        boolean stillFlagged = restingStatus != EntityStatus.FAILED;
        return fenced(id, owner, e -> withEnrichment(rebuild(e, restingStatus, stillFlagged && e.needsReindex(),
                new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(), e.index().indexedAt(), error),
                null, new Entity.Retry(retryCount, nextAttemptAt)), enrichment));
    }

    @Override
    public boolean markContentMissing(String id, String owner, String error) {
        return fenced(id, owner, e -> rebuild(e, EntityStatus.FAILED, false, true,
                new Entity.IndexInfo(e.index().chunkCount(), e.index().embeddingModel(),
                        e.index().indexedAt(), error),
                null, Entity.Retry.zero()));
    }

    @Override
    public int flagNeedsRefetchByKnowledge(String knowledgeId) {
        int flagged = 0;
        for (Entity e : List.copyOf(store.values())) {
            if (!knowledgeId.equals(e.knowledgeId()) || e.status() == EntityStatus.DELETED
                    || e.content() == null || !e.content().isFile()) {
                continue;
            }
            store.put(e.id(), rebuild(e, e.status(), e.needsReindex(), true, e.index(), e.lease(),
                    e.retry()));
            flagged++;
        }
        return flagged;
    }

    @Override
    public void flagNeedsReindex(String id) {
        mutate(id, e -> {
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
    public int flagNeedsReindexByKnowledge(String knowledgeId) {
        int flagged = 0;
        Instant now = Instant.now();
        for (Entity e : List.copyOf(store.values())) {
            if (!knowledgeId.equals(e.knowledgeId()) || e.status() == EntityStatus.DELETED) {
                continue;
            }
            if (e.lease() != null && e.lease().isLiveAt(now)) {
                continue;
            }
            store.put(e.id(), rebuild(e, e.status(), true, e.index(), e.lease(), Entity.Retry.zero()));
            flagged++;
        }
        return flagged;
    }

    @Override
    public List<Entity> findExpired(int limit, Instant now) {
        return store.values().stream()
                .filter(e -> e.expiresAt() != null && !e.expiresAt().isAfter(now))
                .filter(e -> e.status() != EntityStatus.DELETED)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Entity> findCreatedBefore(String knowledgeId, Instant cutoff, int limit) {
        return store.values().stream()
                .filter(e -> knowledgeId.equals(e.knowledgeId()))
                .filter(e -> e.expiresAt() == null)
                .filter(e -> e.createdAt() != null && e.createdAt().isBefore(cutoff))
                .filter(e -> e.status() != EntityStatus.DELETED)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Entity> findByStatus(EntityStatus status, int limit) {
        return store.values().stream().filter(e -> e.status() == status).limit(limit).toList();
    }

    @Override
    public List<EntitySummary> findByKnowledge(String knowledgeId, EntityQuery query, int limit, int offset) {
        return store.values().stream()
                .filter(e -> matchesListing(e, knowledgeId, query))
                .sorted(Comparator.comparing(Entity::updatedAt).reversed().thenComparing(Entity::id))
                .skip(offset)
                .limit(limit)
                .map(InMemoryEntityRepository::toSummary)
                .toList();
    }

    @Override
    public long countByKnowledge(String knowledgeId, EntityQuery query) {
        return store.values().stream().filter(e -> matchesListing(e, knowledgeId, query)).count();
    }

    private static boolean matchesListing(Entity e, String knowledgeId, EntityQuery query) {
        if (!e.knowledgeId().equals(knowledgeId)) {
            return false;
        }
        if (query.hasStatusFilter() ? !query.statuses().contains(e.status())
                : e.status() == EntityStatus.DELETED) {
            return false;
        }
        if (query.hasIterableFilter() && !query.iterableIds().contains(e.iterableId())) {
            return false;
        }
        if (!query.hasTextFilter()) {
            return true;
        }
        String needle = query.titleContains().toLowerCase();
        return contains(e.title(), needle) || contains(e.externalId(), needle);
    }

    private static boolean contains(String value, String lowercaseNeedle) {
        return value != null && value.toLowerCase().contains(lowercaseNeedle);
    }

    private static EntitySummary toSummary(Entity e) {
        return new EntitySummary(e.id(), e.knowledgeId(), e.iterableId(), e.externalId(), e.entityType(),
                e.status(),
                e.title(), e.uri(), e.checksum(),
                e.index() == null ? Entity.IndexInfo.empty() : e.index(),
                e.retry() == null ? 0 : e.retry().count(),
                e.needsReindex(), e.createdAt(), e.updatedAt(),
                e.enrichment() == null ? null : e.enrichment().error());
    }

    @Override
    public List<Entity> findMatching(EntityFilter filter, int limit, int offset) {
        Comparator<Entity> order = Comparator.comparing(e -> comparable(valueAt(e, filter.sort().path())),
                Comparator.nullsLast(Comparator.naturalOrder()));
        if (filter.sort().descending()) {
            order = order.reversed();
        }
        return store.values().stream()
                .filter(e -> matches(e, filter))
                .sorted(order.thenComparing(Entity::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countMatching(EntityFilter filter) {
        return store.values().stream().filter(e -> matches(e, filter)).count();
    }

    @Override
    public Map<String, List<FacetValue>> facets(EntityFilter filter, List<String> paths, int limitPerPath) {
        Map<String, List<FacetValue>> out = new LinkedHashMap<>();
        for (String path : paths) {
            Map<Object, Long> counts = new LinkedHashMap<>();
            store.values().stream().filter(e -> matches(e, filter)).forEach(e -> {
                Object v = valueAt(e, path);
                List<?> elements = v instanceof List<?> list ? list : java.util.Collections.singletonList(v);
                for (Object element : elements) {
                    if (element != null) {
                        counts.merge(element, 1L, Long::sum);
                    }
                }
            });
            out.put(path, counts.entrySet().stream()
                    .sorted(Map.Entry.<Object, Long>comparingByValue().reversed())
                    .limit(limitPerPath)
                    .map(en -> new FacetValue(en.getKey(), en.getValue()))
                    .toList());
        }
        return out;
    }

    @Override
    public boolean mergeCustom(String id, Map<String, Object> values) {
        Entity e = store.get(id);
        if (e == null) {
            return false;
        }
        Map<String, Object> custom = new LinkedHashMap<>(e.custom());
        values.forEach((k, v) -> {
            if (v == null) {
                custom.remove(k);
            } else {
                custom.put(k, v);
            }
        });
        store.put(id, new Entity(e.id(), e.knowledgeId(), e.iterableId(), e.entityType(), e.externalId(),
                e.raw(), e.content(), e.metadata(), e.checksum(), e.status(), e.needsReindex(),
                e.needsRefetch(), e.index(), e.lease(), e.retry(), e.createdAt(), e.updatedAt(),
                e.expiresAt(), e.lastSeenGeneration(), e.enriched(), e.enrichment(), custom));
        return true;
    }

    private static boolean matches(Entity e, EntityFilter filter) {
        if (e.status() == EntityStatus.DELETED) {
            return false;
        }
        if (!filter.entityTypes().isEmpty() && !filter.entityTypes().contains(e.entityType())) {
            return false;
        }
        if (!filter.knowledgeIds().isEmpty() && !filter.knowledgeIds().contains(e.knowledgeId())) {
            return false;
        }
        if (filter.text() != null) {
            String needle = filter.text().toLowerCase();
            boolean hit = (e.title() != null && e.title().toLowerCase().contains(needle))
                    || e.externalId().toLowerCase().contains(needle);
            if (!hit) {
                return false;
            }
        }
        return filter.conditions().stream().allMatch(c -> holds(valueAt(e, c.path()), c));
    }

    private static boolean holds(Object actual, EntityFilter.Condition c) {
        List<?> values = actual instanceof List<?> list ? list : java.util.Collections.singletonList(actual);
        return switch (c.op()) {
            case EQ -> values.stream().anyMatch(v -> java.util.Objects.equals(v, c.value()));
            case NE -> values.stream().noneMatch(v -> java.util.Objects.equals(v, c.value()));
            case IN -> values.stream().anyMatch(v -> v != null && ((List<?>) c.value()).contains(v));
            case NIN -> values.stream().noneMatch(v -> v != null && ((List<?>) c.value()).contains(v));
            case GTE -> compare(actual, c.value()) >= 0;
            case GT -> compare(actual, c.value()) > 0;
            case LTE -> actual != null && compare(actual, c.value()) <= 0;
            case LT -> actual != null && compare(actual, c.value()) < 0;
            case CONTAINS -> actual != null
                    && actual.toString().toLowerCase().contains(c.value().toString().toLowerCase());
            case EXISTS -> (actual != null) == Boolean.TRUE.equals(c.value());
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object actual, Object bound) {
        if (actual == null) {
            return -1;
        }
        if (actual instanceof Number a && bound instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return ((Comparable) actual).compareTo(bound);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable comparable(Object value) {
        return value instanceof Comparable c ? c : null;
    }

    private static Object valueAt(Entity e, String path) {
        return switch (path) {
            case "createdAt" -> e.createdAt();
            case "updatedAt" -> e.updatedAt();
            default -> {
                int dot = path.indexOf('.');
                String key = path.substring(dot + 1);
                Map<String, Object> map = switch (path.substring(0, dot)) {
                    case "metadata" -> e.metadata();
                    case "enriched" -> e.enriched();
                    default -> e.custom();
                };
                yield map == null ? null : map.get(key);
            }
        };
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
        if (e.needsReindex() && e.status() != EntityStatus.DELETED && e.status() != EntityStatus.INDEXING
                && e.status() != EntityStatus.FAILED) {
            return true;
        }
        return e.status() == EntityStatus.INDEXING && (e.lease() == null || !e.lease().isLiveAt(now));
    }

    /** Stores the entity as given, without the work-queue reset {@link #upsert} applies. */
    public void seed(Entity entity) {
        store.put(entity.id(), entity);
    }

    /** Skips the lease protocol; a test of the protocol should claim first and pass the real owner. */
    public void seedIndexed(String id, int chunkCount, String embeddingModel, Instant indexedAt) {
        mutate(id, e -> rebuild(e, EntityStatus.INDEXED, false,
                new Entity.IndexInfo(chunkCount, embeddingModel, indexedAt, null), null, Entity.Retry.zero()));
    }

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
        return rebuild(e, status, needsReindex, e.needsRefetch(), index, lease, retry);
    }

    private static Entity rebuild(Entity e, EntityStatus status, boolean needsReindex,
                                  boolean needsRefetch, Entity.IndexInfo index, Entity.Lease lease,
                                  Entity.Retry retry) {
        return new Entity(e.id(), e.knowledgeId(), e.iterableId(), e.entityType(), e.externalId(),
                e.raw(), e.content(), e.metadata(), e.checksum(), status, needsReindex, needsRefetch,
                index, lease, retry, e.createdAt(), Instant.now(), e.expiresAt(), e.lastSeenGeneration(),
                e.enriched(), e.enrichment(), e.custom());
    }
}
