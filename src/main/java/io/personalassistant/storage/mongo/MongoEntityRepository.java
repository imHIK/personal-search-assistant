package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntitySummary;
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
        // One atomic findOneAndUpdate on the natural key rather than find-then-replaceOne. Two
        // reasons: the unique (knowledgeId, externalId) index makes the old read-then-write racy
        // (two walkers that both miss produce a duplicate-key error on the second write), and a
        // whole-document replace clobbers indexer-owned fields — see the lease unset in upsertUpdate.
        Bson filter = and(eq("knowledgeId", entity.knowledgeId()), eq("externalId", entity.externalId()));
        Document stored = collection().findOneAndUpdate(filter, upsertUpdate(entity),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return fromDoc(stored);
    }

    // Package-private so the emitted BSON can be asserted without a live MongoDB.
    Bson upsertUpdate(Entity entity) {
        Entity.Content c = entity.content() == null ? new Entity.Content(null, null) : entity.content();
        return Updates.combine(
                Updates.setOnInsert("_id", entity.id()),
                Updates.setOnInsert("createdAt", BsonSupport.date(entity.createdAt())),
                // --- ingestion-owned: the item's content and change-detection state ---
                Updates.set("iterableId", entity.iterableId()),
                Updates.set("entityType", BsonSupport.enumName(entity.entityType())),
                Updates.set("raw", BsonSupport.toBsonMap(entity.raw())),
                Updates.set("content", new Document("text", c.text()).append("fileRef", c.fileRef())),
                Updates.set("metadata", BsonSupport.toBsonMap(entity.metadata())),
                Updates.set("checksum", entity.checksum()),
                Updates.set("lastSeenGeneration", entity.lastSeenGeneration()),
                Updates.set("updatedAt", BsonSupport.date(entity.updatedAt())),
                // --- new content invalidates whatever was in flight: reset the work queue ---
                Updates.set("status", EntityStatus.INGESTED.name()),
                Updates.set("needsReindex", false),
                Updates.set("retry", zeroRetry()),
                Updates.set("index.error", null),
                // --- and fence out a worker still chewing on the OLD text ---
                // Its markIndexed is lease-fenced, so dropping the lease here makes that write a
                // no-op. Without this the indexer finishes on stale content, marks the entity
                // INDEXED with needsReindex=false, and the new content never reaches OpenSearch.
                Updates.unset("lease"));
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
                // FAILED is excluded even with needsReindex set: it is the dead-letter state, and a
                // dead-lettered entity has a null nextAttemptAt that reads as "backoff elapsed", so
                // it would otherwise be re-claimed every tick with no retry budget left to spend.
                // markFailed also clears the flag; this clause is what protects rows already written
                // by an older build. flagNeedsReindex is the sanctioned way back in.
                and(eq("needsReindex", true),
                        nin("status", EntityStatus.DELETED.name(), EntityStatus.INDEXING.name(),
                                EntityStatus.FAILED.name())),
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
    public boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt) {
        var result = collection().updateOne(ownedBy(id, owner),
                indexedUpdate(chunkCount, embeddingModel, indexedAt));
        return result.getMatchedCount() > 0;
    }

    // Package-private so the emitted BSON can be asserted without a live MongoDB.
    Bson indexedUpdate(int chunkCount, String embeddingModel, Instant indexedAt) {
        return Updates.combine(
                Updates.set("status", EntityStatus.INDEXED.name()),
                Updates.set("needsReindex", false),
                Updates.set("index", new Document("chunkCount", chunkCount)
                        .append("embeddingModel", embeddingModel)
                        .append("indexedAt", BsonSupport.date(indexedAt))
                        .append("error", null)),
                // Success ends the streak: retry.count is CONSECUTIVE failures, not lifetime ones.
                // Without this an entity that fails once a month is dead-lettered after five months
                // of otherwise-successful indexing.
                Updates.set("retry", zeroRetry()),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now())));
    }

    @Override
    public boolean markDeletionComplete(String id, String owner, Instant cleanedAt) {
        var result = collection().updateOne(ownedBy(id, owner), Updates.combine(
                Updates.set("needsReindex", false),
                Updates.set("index.chunkCount", 0),
                Updates.set("index.indexedAt", BsonSupport.date(cleanedAt)),
                Updates.set("retry", zeroRetry()),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean markFailed(String id, String owner, EntityStatus restingStatus, String error,
                              int retryCount, Instant nextAttemptAt) {
        var result = collection().updateOne(ownedBy(id, owner),
                failUpdate(restingStatus, error, retryCount, nextAttemptAt));
        return result.getMatchedCount() > 0;
    }

    // Package-private so the emitted BSON can be asserted without a live MongoDB.
    Bson failUpdate(EntityStatus restingStatus, String error, int retryCount, Instant nextAttemptAt) {
        List<Bson> updates = new ArrayList<>(List.of(
                Updates.set("status", restingStatus.name()),
                Updates.set("index.error", error),
                Updates.set("retry", new Document("count", retryCount)
                        .append("nextAttemptAt", BsonSupport.date(nextAttemptAt))),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
        if (restingStatus == EntityStatus.FAILED) {
            // Dead-letter: leave the indexing queue for good. Without this the entity still matches
            // indexingFilter's needsReindex clause and — since a null nextAttemptAt reads as "backoff
            // elapsed" — is re-claimed on every single tick, forever, burning the batch budget.
            updates.add(Updates.set("needsReindex", false));
        }
        return Updates.combine(updates);
    }

    @Override
    public void flagNeedsReindex(String id) {
        // Revive a dead-lettered entity first: FAILED is excluded from indexingFilter, so flagging it
        // without this would strand the entity instead of re-queueing it. Scoped to FAILED so a
        // healthy entity's status is untouched.
        collection().updateOne(and(eq("_id", id), eq("status", EntityStatus.FAILED.name())),
                Updates.combine(
                        Updates.set("status", EntityStatus.INGESTED.name()),
                        Updates.set("index.error", null)));
        // Fresh retry budget — a manual reindex of a dead-lettered entity that kept its exhausted
        // counter would fail again on the first hiccup. Deliberately does NOT touch the lease: an
        // entity mid-run stays out of the queue (indexingFilter excludes INDEXING) until it lapses.
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("needsReindex", true),
                Updates.set("retry", zeroRetry()),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void stampLastSeen(String id, long generation) {
        // Cheap single-field touch on the change-detection skip path — deliberately does NOT bump
        // updatedAt (this is walk bookkeeping, not a content change).
        collection().updateOne(eq("_id", id), Updates.set("lastSeenGeneration", generation));
    }

    @Override
    public int retryFailedByKnowledge(String knowledgeId) {
        var result = collection().updateMany(
                and(eq("knowledgeId", knowledgeId), eq("status", EntityStatus.FAILED.name())),
                Updates.combine(
                        Updates.set("status", EntityStatus.INGESTED.name()),
                        Updates.set("retry", zeroRetry()),
                        Updates.set("index.error", null),
                        Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
        // needsReindex is deliberately left alone: INGESTED already matches indexingFilter's first
        // clause, so setting it would be redundant state with nothing to clear it.
        return (int) result.getModifiedCount();
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
    public List<EntitySummary> findByKnowledge(String knowledgeId, EntityStatus status, int limit, int offset) {
        Bson filter = status == null ? eq("knowledgeId", knowledgeId)
                : and(eq("knowledgeId", knowledgeId), eq("status", status.name()));
        List<EntitySummary> out = new ArrayList<>();
        collection().find(filter)
                // Project away raw + content: they are the bulk of the document and a listing never
                // reads them. _id comes back implicitly.
                .projection(Projections.include("knowledgeId", "externalId", "entityType", "status",
                        "metadata.title", "metadata.uri", "checksum", "index", "retry.count",
                        "needsReindex", "updatedAt"))
                // _id is the tiebreak so two entities touched in the same millisecond can't swap
                // places between pages. Served by the (knowledgeId, updatedAt, _id) compound index.
                .sort(orderBy(descending("updatedAt"), ascending("_id")))
                .skip(offset)
                .limit(limit)
                .forEach(d -> out.add(toSummary(d)));
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

    private static Document zeroRetry() {
        return new Document("count", 0).append("nextAttemptAt", null);
    }

    /**
     * Lease fence, mirroring {@code MongoCursorRepository.ownedBy}: matches the entity only if
     * {@code owner} still holds a live (non-expired) lease. A worker whose lease lapsed — and whose
     * entity was re-claimed by someone else — matches nothing, so its late writes are no-ops rather
     * than marking a half-written entity INDEXED and unsetting the new owner's lease mid-run.
     */
    // Package-private so the emitted BSON can be asserted without a live MongoDB.
    static Bson ownedBy(String id, String owner) {
        return and(eq("_id", id), eq("lease.owner", owner),
                gt("lease.expiresAt", BsonSupport.date(Instant.now())));
    }

    // ---- mapping -----------------------------------------------------------------------------

    // There is deliberately no toDoc(): every write is a field-level update so that ingestion and the
    // indexer can own disjoint field sets on the same document (see upsert). A whole-document mapper
    // would be a standing invitation to reintroduce the clobber it was removed to fix.

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

    /**
     * Map a projected listing document. Reads {@code title}/{@code uri} out of the projected
     * {@code metadata} sub-document rather than via {@link Entity#title()} — the projection carries
     * only those two keys, so there is no full entity to ask.
     */
    private EntitySummary toSummary(Document d) {
        Document metadata = BsonSupport.sub(d, "metadata");
        Document idx = BsonSupport.sub(d, "index");
        Document retry = BsonSupport.sub(d, "retry");
        return new EntitySummary(
                d.getString("_id"),
                d.getString("knowledgeId"),
                d.getString("externalId"),
                BsonSupport.enumOf(EntityType.class, d.get("entityType")),
                BsonSupport.enumOf(EntityStatus.class, d.get("status")),
                metadata == null ? null : metadata.getString("title"),
                metadata == null ? null : metadata.getString("uri"),
                d.getString("checksum"),
                idx == null ? Entity.IndexInfo.empty() : new Entity.IndexInfo(
                        intValue(idx.get("chunkCount")), idx.getString("embeddingModel"),
                        BsonSupport.instant(idx.get("indexedAt")), idx.getString("error")),
                retry == null ? 0 : intValue(retry.get("count")),
                Boolean.TRUE.equals(d.getBoolean("needsReindex")),
                BsonSupport.instant(d.get("updatedAt")));
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
