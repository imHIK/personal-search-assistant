package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.EnrichmentOutcome;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityQuery;
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
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
        // One atomic findOneAndUpdate on the natural key: find-then-replace races on the unique index, and a
        // whole-document replace clobbers indexer-owned fields.
        Bson filter = and(eq("knowledgeId", entity.knowledgeId()), eq("externalId", entity.externalId()));
        Document stored = collection().findOneAndUpdate(filter, upsertUpdate(entity),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return fromDoc(stored);
    }

    Bson upsertUpdate(Entity entity) {
        Entity.Content c = entity.content() == null ? new Entity.Content(null, null) : entity.content();
        return Updates.combine(
                Updates.setOnInsert("_id", entity.id()),
                Updates.setOnInsert("createdAt", BsonSupport.date(entity.createdAt())),
                // Ingestion-owned: content and change-detection state.
                Updates.set("iterableId", entity.iterableId()),
                Updates.set("entityType", BsonSupport.enumName(entity.entityType())),
                Updates.set("raw", BsonSupport.toBsonMap(entity.raw())),
                Updates.set("content", new Document("text", c.text()).append("fileRef", c.fileRef())),
                Updates.set("metadata", BsonSupport.toBsonMap(entity.metadata())),
                Updates.set("checksum", entity.checksum()),
                // Only the source knows when an item stops being valid, and a re-walk that reports none must
                // clear a stale value.
                Updates.set("expiresAt", BsonSupport.date(entity.expiresAt())),
                Updates.set("lastSeenGeneration", entity.lastSeenGeneration()),
                Updates.set("updatedAt", BsonSupport.date(entity.updatedAt())),
                // New content resets the work queue.
                Updates.set("status", EntityStatus.INGESTED.name()),
                Updates.set("needsReindex", false),
                // The content was just materialized, so the distrust is spent; clearing it only here keeps
                // the flag from forcing a re-fetch on every walk.
                Updates.set("needsRefetch", false),
                Updates.set("retry", zeroRetry()),
                Updates.set("index.error", null),
                // Dropping the lease fences out a worker still on the old text: its markIndexed becomes a
                // no-op instead of marking stale content INDEXED.
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

    private Bson indexingFilter(Instant now) {
        // An entity awaiting retry is claimable only once its nextAttemptAt passes.
        Bson backoffReady = or(eq("retry.nextAttemptAt", null), lte("retry.nextAttemptAt", BsonSupport.date(now)));
        return and(backoffReady, or(
                eq("status", EntityStatus.INGESTED.name()),
                // FAILED is excluded even with needsReindex set: its null nextAttemptAt reads as backoff
                // elapsed, so it would be re-claimed every tick. flagNeedsReindex is the sanctioned way back.
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
    public boolean markIndexed(String id, String owner, int chunkCount, String embeddingModel, Instant indexedAt,
                               EnrichmentOutcome enrichment) {
        var result = collection().updateOne(ownedBy(id, owner),
                indexedUpdate(chunkCount, embeddingModel, indexedAt, enrichment));
        return result.getMatchedCount() > 0;
    }

    Bson indexedUpdate(int chunkCount, String embeddingModel, Instant indexedAt) {
        return indexedUpdate(chunkCount, embeddingModel, indexedAt, EnrichmentOutcome.keep());
    }

    Bson indexedUpdate(int chunkCount, String embeddingModel, Instant indexedAt, EnrichmentOutcome enrichment) {
        List<Bson> updates = new ArrayList<>(enrichmentUpdates(enrichment));
        updates.add(indexedFields(chunkCount, embeddingModel, indexedAt));
        return Updates.combine(updates);
    }

    private static List<Bson> enrichmentUpdates(EnrichmentOutcome outcome) {
        return switch (outcome.kind()) {
            case KEEP -> List.of();
            case CLEAR -> List.of(Updates.unset("enriched"), Updates.unset("enrichment"));
            case SET -> List.of(Updates.set("enriched", BsonSupport.toBsonMap(outcome.values())),
                    Updates.set("enrichment", enrichmentDoc(outcome.stamp())));
            case ERROR -> List.of(Updates.set("enrichment", enrichmentDoc(outcome.stamp())));
        };
    }

    private static Document enrichmentDoc(Entity.Enrichment e) {
        return new Document("taskId", e.taskId())
                .append("taskVersion", BsonSupport.date(e.taskVersion()))
                .append("checksum", e.checksum())
                .append("at", BsonSupport.date(e.at()))
                .append("error", e.error());
    }

    private static Bson indexedFields(int chunkCount, String embeddingModel, Instant indexedAt) {
        return Updates.combine(
                Updates.set("status", EntityStatus.INDEXED.name()),
                Updates.set("needsReindex", false),
                Updates.set("index", new Document("chunkCount", chunkCount)
                        .append("embeddingModel", embeddingModel)
                        .append("indexedAt", BsonSupport.date(indexedAt))
                        .append("error", null)),
                // Success ends the streak: retry.count is consecutive failures.
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

    Bson failUpdate(EntityStatus restingStatus, String error, int retryCount, Instant nextAttemptAt) {
        List<Bson> updates = new ArrayList<>(List.of(
                Updates.set("status", restingStatus.name()),
                Updates.set("index.error", error),
                Updates.set("retry", new Document("count", retryCount)
                        .append("nextAttemptAt", BsonSupport.date(nextAttemptAt))),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
        if (restingStatus == EntityStatus.FAILED) {
            // A dead letter leaves the queue for good; otherwise needsReindex plus a null nextAttemptAt would
            // re-claim it every tick.
            updates.add(Updates.set("needsReindex", false));
        }
        return Updates.combine(updates);
    }

    @Override
    public boolean markContentMissing(String id, String owner, String error) {
        var result = collection().updateOne(ownedBy(id, owner), contentMissingUpdate(error));
        return result.getMatchedCount() > 0;
    }

    Bson contentMissingUpdate(String error) {
        return Updates.combine(
                // Terminal at once: backoff cannot bring a purged staged copy back.
                Updates.set("status", EntityStatus.FAILED.name()),
                Updates.set("needsReindex", false),
                // Recoverable, though: the next walk re-materializes it despite an unchanged checksum.
                Updates.set("needsRefetch", true),
                Updates.set("index.error", error),
                Updates.set("retry", new Document("count", 0).append("nextAttemptAt", null)),
                Updates.unset("lease"),
                Updates.set("updatedAt", BsonSupport.date(Instant.now())));
    }

    @Override
    public int flagNeedsRefetchByKnowledge(String knowledgeId) {
        // File-backed only: inline text needs no re-fetch.
        Bson filter = and(
                eq("knowledgeId", knowledgeId),
                ne("status", EntityStatus.DELETED.name()),
                ne("content.fileRef", null));
        var result = collection().updateMany(filter, Updates.combine(
                Updates.set("needsRefetch", true),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
        return (int) result.getModifiedCount();
    }

    @Override
    public void flagNeedsReindex(String id) {
        // Revive a FAILED entity first: FAILED is excluded from indexingFilter, so flagging alone would
        // strand it.
        collection().updateOne(and(eq("_id", id), eq("status", EntityStatus.FAILED.name())),
                Updates.combine(
                        Updates.set("status", EntityStatus.INGESTED.name()),
                        Updates.set("index.error", null)));
        // A fresh retry budget. The lease is left alone: an entity mid-run stays out of the queue until it
        // lapses.
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("needsReindex", true),
                Updates.set("retry", zeroRetry()),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void stampLastSeen(String id, long generation) {
        // Leaves updatedAt alone: walk bookkeeping, not a content change.
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
        // needsReindex is left alone: INGESTED already matches indexingFilter.
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
    public int flagNeedsReindexByKnowledge(String knowledgeId) {
        Instant now = Instant.now();
        // A live lease means a worker is mid-run, and flagging it would race its terminal write; a later call
        // picks those up.
        Bson filter = and(
                eq("knowledgeId", knowledgeId),
                ne("status", EntityStatus.DELETED.name()),
                or(eq("lease", null), lt("lease.expiresAt", BsonSupport.date(now))));
        var result = collection().updateMany(filter, Updates.combine(
                Updates.set("needsReindex", true),
                Updates.set("retry", zeroRetry()),
                Updates.set("index.error", null),
                Updates.set("updatedAt", BsonSupport.date(now))));
        return (int) result.getModifiedCount();
    }

    @Override
    public List<Entity> findExpired(int limit, Instant now) {
        Bson filter = and(
                ne("expiresAt", null),
                lte("expiresAt", BsonSupport.date(now)),
                ne("status", EntityStatus.DELETED.name()));
        return find(filter, limit);
    }

    @Override
    public List<Entity> findCreatedBefore(String knowledgeId, Instant cutoff, int limit) {
        // Only entities without their own expiresAt: findExpired owns those, and the window would age out an
        // item the source says is still good.
        Bson filter = and(
                eq("knowledgeId", knowledgeId),
                eq("expiresAt", null),
                lt("createdAt", BsonSupport.date(cutoff)),
                ne("status", EntityStatus.DELETED.name()));
        return find(filter, limit);
    }

    private List<Entity> find(Bson filter, int limit) {
        List<Entity> out = new ArrayList<>();
        collection().find(filter).limit(limit).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Entity> findByStatus(EntityStatus status, int limit) {
        List<Entity> out = new ArrayList<>();
        collection().find(eq("status", status.name())).limit(limit).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    /**
     * Shared by the listing and its count, so they cannot disagree. The regex cannot use an index but only
     * scans one knowledge; {@code Pattern.quote} keeps C++ or ( from erroring or matching as a pattern.
     */
    private static Bson listingFilter(String knowledgeId, EntityQuery query) {
        List<Bson> clauses = new ArrayList<>();
        clauses.add(eq("knowledgeId", knowledgeId));
        if (query.hasStatusFilter()) {
            clauses.add(in("status", query.statuses().stream().map(EntityStatus::name).toList()));
        } else {
            clauses.add(ne("status", EntityStatus.DELETED.name()));
        }
        if (query.hasIterableFilter()) {
            clauses.add(in("iterableId", query.iterableIds()));
        }
        if (query.hasTextFilter()) {
            String quoted = Pattern.quote(query.titleContains());
            clauses.add(or(regex("metadata.title", quoted, "i"), regex("externalId", quoted, "i")));
        }
        return and(clauses);
    }

    @Override
    public List<EntitySummary> findByKnowledge(String knowledgeId, EntityQuery query, int limit, int offset) {
        List<EntitySummary> out = new ArrayList<>();
        collection().find(listingFilter(knowledgeId, query))
                // raw and content are the bulk of a document, and a listing never reads them.
                .projection(Projections.include("knowledgeId", "iterableId", "externalId", "entityType",
                        "status", "metadata.title", "metadata.uri", "checksum", "index", "retry.count",
                        "needsReindex", "createdAt", "updatedAt", "enrichment.error"))
                // _id is the tiebreak, so entities touched in the same millisecond cannot swap places between
                // pages.
                .sort(orderBy(descending("updatedAt"), ascending("_id")))
                .skip(offset)
                .limit(limit)
                .forEach(d -> out.add(toSummary(d)));
        return out;
    }

    @Override
    public long countByKnowledge(String knowledgeId, EntityQuery query) {
        return collection().countDocuments(listingFilter(knowledgeId, query));
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
     * Only while {@code owner} holds a live lease: a lapsed worker's late write is a no-op rather than
     * marking a half-written entity INDEXED.
     */
    static Bson ownedBy(String id, String owner) {
        return and(eq("_id", id), eq("lease.owner", owner),
                gt("lease.expiresAt", BsonSupport.date(Instant.now())));
    }

    // No toDoc(), deliberately: every write is field-level, so ingestion and the indexer own disjoint fields.
    // A whole-document mapper would invite back the clobber it was removed to fix.

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
                Boolean.TRUE.equals(d.getBoolean("needsRefetch")),
                idx == null ? Entity.IndexInfo.empty() : new Entity.IndexInfo(
                        intValue(idx.get("chunkCount")), idx.getString("embeddingModel"),
                        BsonSupport.instant(idx.get("indexedAt")), idx.getString("error")),
                lease == null ? null : new Entity.Lease(lease.getString("owner"),
                        BsonSupport.instant(lease.get("expiresAt"))),
                retry == null ? Entity.Retry.zero() : new Entity.Retry(
                        intValue(retry.get("count")), BsonSupport.instant(retry.get("nextAttemptAt"))),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")),
                BsonSupport.instant(d.get("expiresAt")),
                longValue(d.get("lastSeenGeneration")),
                BsonSupport.toPlainMap(d.get("enriched")),
                enrichmentOf(BsonSupport.sub(d, "enrichment")),
                BsonSupport.toPlainMap(d.get("custom")));
    }

    private static Entity.Enrichment enrichmentOf(Document e) {
        return e == null ? null : new Entity.Enrichment(e.getString("taskId"),
                BsonSupport.instant(e.get("taskVersion")), e.getString("checksum"),
                BsonSupport.instant(e.get("at")), e.getString("error"));
    }

    private EntitySummary toSummary(Document d) {
        Document metadata = BsonSupport.sub(d, "metadata");
        Document idx = BsonSupport.sub(d, "index");
        Document retry = BsonSupport.sub(d, "retry");
        Document enrichment = BsonSupport.sub(d, "enrichment");
        return new EntitySummary(
                d.getString("_id"),
                d.getString("knowledgeId"),
                d.getString("iterableId"),
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
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")),
                enrichment == null ? null : enrichment.getString("error"));
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
