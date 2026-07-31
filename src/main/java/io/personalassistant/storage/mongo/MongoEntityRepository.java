package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.or;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MongoDB adapter for {@link EntityRepository} over the {@code entities} collection. Upserts are
 * keyed on {@code (knowledgeId, externalId)} so a replay overwrites rather than duplicates; the
 * claim methods are atomic so the indexing stage is concurrency- and crash-safe.
 */
@ApplicationScoped
public class MongoEntityRepository implements EntityRepository {

    static final String COLLECTION = "entities";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoEntityRepository(MongoClient mongoClient,
                                 @ConfigProperty(name = "quarkus.mongodb.database",
                                         defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Entity upsert(Entity entity) {
        Document existing = collection().find(
                and(eq("knowledgeId", entity.knowledgeId()), eq("externalId", entity.externalId()))).first();
        String id = existing != null ? existing.getString("_id") : entity.id();
        Instant createdAt = existing != null ? BsonSupport.instant(existing.get("createdAt")) : entity.createdAt();

        Entity toStore = new Entity(id, entity.knowledgeId(), entity.iterableId(), entity.entityType(),
                entity.externalId(), entity.raw(), entity.content(), entity.metadata(), entity.checksum(),
                entity.status(), entity.needsReindex(), entity.index(), entity.lease(), entity.retry(),
                createdAt, entity.updatedAt(), entity.lastSeenGeneration());
        collection().replaceOne(eq("_id", id), toDoc(toStore), new ReplaceOptions().upsert(true));
        return toStore;
    }

    @Override
    public Optional<Entity> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public Optional<Entity> findByKnowledgeAndExternalId(String knowledgeId, String externalId) {
        return Optional.ofNullable(collection().find(
                        and(eq("knowledgeId", knowledgeId), eq("externalId", externalId))).first())
                .map(this::fromDoc);
    }

    @Override
    public List<Entity> claimForIndexing(int limit, String owner, Duration lease) {
        Instant now = Instant.now();
        return claimLoop(indexingFilter(now), indexingUpdate(now, lease, owner), limit);
    }

    @Override
    public List<Entity> claimForIndexing(String knowledgeId, int limit, String owner, Duration lease) {
        Instant now = Instant.now();
        Bson filter = and(eq("knowledgeId", knowledgeId), indexingFilter(now));
        return claimLoop(filter, indexingUpdate(now, lease, owner), limit);
    }

    @Override
    public List<String> distinctPendingKnowledgeIds(int limit) {
        List<String> ids = new ArrayList<>();
        collection().distinct("knowledgeId", indexingFilter(Instant.now()), String.class)
                .forEach(id -> {
                    if (id != null && ids.size() < limit) {
                        ids.add(id);
                    }
                });
        return ids;
    }

    /** Entities awaiting (re)indexing: INGESTED, needsReindex, or stale INDEXING — backoff-aware. */
    private Bson indexingFilter(Instant now) {
        // Honour retry backoff: an entity awaiting retry is only claimable once its nextAttemptAt passes.
        Bson backoffReady = or(eq("retry.nextAttemptAt", null), lte("retry.nextAttemptAt", BsonSupport.date(now)));
        return and(backoffReady, or(
                eq("status", EntityStatus.INGESTED.name()),
                and(eq("needsReindex", true),
                        nin("status", EntityStatus.DELETED.name(), EntityStatus.INDEXING.name())),
                and(eq("status", EntityStatus.INDEXING.name()), lt("lease.expiresAt", BsonSupport.date(now)))));
    }

    private Bson indexingUpdate(Instant now, Duration lease, String owner) {
        return Updates.combine(
                Updates.set("status", EntityStatus.INDEXING.name()),
                Updates.set("lease", leaseDoc(owner, now.plus(lease))),
                Updates.set("updatedAt", BsonSupport.date(now)));
    }

    @Override
    public List<Entity> claimForDeletion(int limit, String owner, Duration lease) {
        Instant now = Instant.now();
        Bson filter = and(eq("status", EntityStatus.DELETED.name()), eq("needsReindex", true),
                or(eq("lease", null), lt("lease.expiresAt", BsonSupport.date(now))));
        Bson update = Updates.combine(
                Updates.set("lease", leaseDoc(owner, now.plus(lease))),
                Updates.set("updatedAt", BsonSupport.date(now)));
        return claimLoop(filter, update, limit);
    }

    private List<Entity> claimLoop(Bson filter, Bson update, int limit) {
        List<Entity> claimed = new ArrayList<>();
        var options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER);
        for (int i = 0; i < limit; i++) {
            Document d = collection().findOneAndUpdate(filter, update, options);
            if (d == null) {
                break;
            }
            claimed.add(fromDoc(d));
        }
        return claimed;
    }

    @Override
    public void markIndexed(String id, int chunkCount, String embeddingModel, Instant indexedAt) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", EntityStatus.INDEXED.name()),
                Updates.set("needsReindex", false),
                Updates.set("index", new Document("chunkCount", chunkCount)
                        .append("embeddingModel", embeddingModel)
                        .append("indexedAt", BsonSupport.date(indexedAt))
                        .append("error", null)),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void markDeletionComplete(String id, Instant cleanedAt) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("needsReindex", false),
                Updates.set("index.chunkCount", 0),
                Updates.set("index.indexedAt", BsonSupport.date(cleanedAt)),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void markFailed(String id, EntityStatus restingStatus, String error, int retryCount, Instant nextAttemptAt) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", restingStatus.name()),
                Updates.set("index.error", error),
                Updates.set("retry", new Document("count", retryCount)
                        .append("nextAttemptAt", BsonSupport.date(nextAttemptAt))),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void flagNeedsReindex(String id) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("needsReindex", true),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void stampLastSeen(String id, long generation) {
        // Cheap single-field touch on the change-detection skip path — deliberately does NOT bump
        // updatedAt (this is walk bookkeeping, not a content change).
        collection().updateOne(eq("_id", id), Updates.set("lastSeenGeneration", generation));
    }

    @Override
    public void markDeleted(String id, Instant updatedAt) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", EntityStatus.DELETED.name()),
                Updates.set("needsReindex", true),
                Updates.set("updatedAt", BsonSupport.date(updatedAt))));
    }

    @Override
    public List<Entity> findByStatus(EntityStatus status, int limit) {
        List<Entity> out = new ArrayList<>();
        collection().find(eq("status", status.name())).limit(limit).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public long countByKnowledgeAndStatus(String knowledgeId, EntityStatus status) {
        return collection().countDocuments(and(eq("knowledgeId", knowledgeId), eq("status", status.name())));
    }

    @Override
    public long countByKnowledge(String knowledgeId) {
        return collection().countDocuments(eq("knowledgeId", knowledgeId));
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        collection().deleteMany(eq("knowledgeId", knowledgeId));
    }

    @Override
    public void deleteByKnowledgeAndIterable(String knowledgeId, String iterableId) {
        collection().deleteMany(and(eq("knowledgeId", knowledgeId), eq("iterableId", iterableId)));
    }

    private static Document leaseDoc(String owner, Instant expiresAt) {
        return new Document("owner", owner).append("expiresAt", BsonSupport.date(expiresAt));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Entity e) {
        Entity.Content c = e.content() == null ? new Entity.Content(null, null) : e.content();
        Entity.IndexInfo idx = e.index() == null ? Entity.IndexInfo.empty() : e.index();
        Entity.Retry retry = e.retry() == null ? Entity.Retry.zero() : e.retry();
        Document lease = e.lease() == null ? null
                : leaseDoc(e.lease().owner(), e.lease().expiresAt());
        return new Document("_id", e.id())
                .append("knowledgeId", e.knowledgeId())
                .append("iterableId", e.iterableId())
                .append("entityType", BsonSupport.enumName(e.entityType()))
                .append("externalId", e.externalId())
                .append("raw", BsonSupport.toBsonMap(e.raw()))
                .append("content", new Document("text", c.text()).append("fileRef", c.fileRef()))
                .append("metadata", BsonSupport.toBsonMap(e.metadata()))
                .append("checksum", e.checksum())
                .append("status", BsonSupport.enumName(e.status()))
                .append("needsReindex", e.needsReindex())
                .append("index", new Document("chunkCount", idx.chunkCount())
                        .append("embeddingModel", idx.embeddingModel())
                        .append("indexedAt", BsonSupport.date(idx.indexedAt()))
                        .append("error", idx.error()))
                .append("lease", lease)
                .append("retry", new Document("count", retry.count())
                        .append("nextAttemptAt", BsonSupport.date(retry.nextAttemptAt())))
                .append("lastSeenGeneration", e.lastSeenGeneration())
                .append("createdAt", BsonSupport.date(e.createdAt()))
                .append("updatedAt", BsonSupport.date(e.updatedAt()));
    }

    private Entity fromDoc(Document d) {
        Document content = BsonSupport.sub(d, "content");
        Document idx = BsonSupport.sub(d, "index");
        Document lease = BsonSupport.sub(d, "lease");
        Document retry = BsonSupport.sub(d, "retry");
        return new Entity(
                d.getString("_id"),
                d.getString("knowledgeId"),
                d.getString("iterableId"),
                BsonSupport.enumOf(EntityType.class, d.get("entityType")),
                d.getString("externalId"),
                BsonSupport.toPlainMap(d.get("raw")),
                new Entity.Content(content == null ? null : content.getString("text"),
                        content == null ? null : content.getString("fileRef")),
                BsonSupport.toPlainMap(d.get("metadata")),
                d.getString("checksum"),
                BsonSupport.enumOf(EntityStatus.class, d.get("status")),
                Boolean.TRUE.equals(d.getBoolean("needsReindex")),
                idx == null ? Entity.IndexInfo.empty() : new Entity.IndexInfo(
                        intValue(idx.get("chunkCount")), idx.getString("embeddingModel"),
                        BsonSupport.instant(idx.get("indexedAt")), idx.getString("error")),
                lease == null ? null : new Entity.Lease(lease.getString("owner"),
                        BsonSupport.instant(lease.get("expiresAt"))),
                retry == null ? Entity.Retry.zero() : new Entity.Retry(
                        intValue(retry.get("count")), BsonSupport.instant(retry.get("nextAttemptAt"))),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")),
                longValue(d.get("lastSeenGeneration")));
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
