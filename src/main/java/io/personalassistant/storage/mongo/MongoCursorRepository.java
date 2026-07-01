package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Sorts.ascending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.storage.repository.CursorRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MongoDB adapter for {@link CursorRepository}. The {@link #claim} and {@link #armForwardCursors}
 * operations are implemented as atomic {@code findOneAndUpdate}/{@code updateMany} so the
 * ingestion loop is safe under concurrency and crash recovery (an expired lease is reclaimable).
 */
@ApplicationScoped
public class MongoCursorRepository implements CursorRepository {

    static final String COLLECTION = "cursors";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoCursorRepository(MongoClient mongoClient,
                                 @ConfigProperty(name = "quarkus.mongodb.database",
                                         defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public boolean insertIfAbsent(Cursor cursor) {
        // _id comes from the filter on insert; including it in $setOnInsert would conflict.
        Document onInsert = toDoc(cursor);
        onInsert.remove("_id");
        var result = collection().updateOne(eq("_id", cursor.id()),
                new Document("$setOnInsert", onInsert),
                new UpdateOptions().upsert(true));
        return result.getUpsertedId() != null;
    }

    @Override
    public Optional<Cursor> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Cursor> findByKnowledge(String knowledgeId) {
        List<Cursor> out = new ArrayList<>();
        collection().find(eq("knowledgeId", knowledgeId)).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Cursor> findClaimable(int limit) {
        List<Cursor> out = new ArrayList<>();
        // Fairness: least-recently-run first. Never-run cursors (null lastRunAt) sort first in
        // Mongo ascending order, so fresh work is picked up promptly and no active knowledge can
        // monopolise the bounded batch.
        collection().find(claimableFilter(Instant.now()))
                .sort(ascending("stats.lastRunAt"))
                .limit(limit)
                .forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public Optional<Cursor> claim(String cursorId, String owner, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant expiry = now.plus(leaseDuration);
        Bson filter = and(eq("_id", cursorId), claimableFilter(now));
        Bson update = Updates.combine(
                Updates.set("status", CursorStatus.IN_PROGRESS.name()),
                Updates.set("lease", new Document("owner", owner).append("expiresAt", BsonSupport.date(expiry))));
        Document updated = collection().findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(updated).map(this::fromDoc);
    }

    @Override
    public void renewLease(String cursorId, String owner, Instant newExpiry) {
        collection().updateOne(and(eq("_id", cursorId), eq("lease.owner", owner)),
                Updates.set("lease.expiresAt", BsonSupport.date(newExpiry)));
    }

    @Override
    public boolean advancePosition(String cursorId, String owner, CursorPosition position,
                                   long fetchedDelta, Instant lastRunAt, Instant newExpiry) {
        var result = collection().updateOne(ownedBy(cursorId, owner), Updates.combine(
                Updates.set("position", BsonSupport.toBsonMap(position.values())),
                Updates.inc("stats.fetched", fetchedDelta),
                Updates.set("stats.lastRunAt", BsonSupport.date(lastRunAt)),
                Updates.set("lease.expiresAt", BsonSupport.date(newExpiry))));
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean release(String cursorId, String owner, CursorStatus restingStatus) {
        var result = collection().updateOne(ownedBy(cursorId, owner), Updates.combine(
                Updates.set("status", restingStatus.name()),
                Updates.unset("lease")));
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean recordFailure(String cursorId, String owner, CursorStatus restingStatus, int retryCount,
                                 String lastError) {
        var result = collection().updateOne(ownedBy(cursorId, owner), Updates.combine(
                Updates.set("status", restingStatus.name()),
                Updates.set("retry.count", retryCount),
                Updates.set("retry.lastError", lastError),
                Updates.unset("lease")));
        return result.getMatchedCount() > 0;
    }

    /**
     * Lease fence: matches the cursor only if {@code owner} still holds a live (non-expired) lease.
     * A worker whose lease lapsed (and was re-claimed by another worker) matches nothing, so its
     * late writes are no-ops instead of clobbering the new owner.
     */
    private static Bson ownedBy(String cursorId, String owner) {
        return and(eq("_id", cursorId), eq("lease.owner", owner),
                gt("lease.expiresAt", BsonSupport.date(Instant.now())));
    }

    @Override
    public int armForwardCursors(String knowledgeId) {
        var result = collection().updateMany(
                and(eq("knowledgeId", knowledgeId),
                        eq("direction", CursorDirection.FORWARD.name()),
                        eq("status", CursorStatus.IDLE.name())),
                Updates.set("status", CursorStatus.AVAILABLE.name()));
        return (int) result.getModifiedCount();
    }

    @Override
    public int suspendByKnowledge(String knowledgeId) {
        var result = collection().updateMany(
                and(eq("knowledgeId", knowledgeId),
                        in("status", CursorStatus.AVAILABLE.name(), CursorStatus.IDLE.name())),
                Updates.set("status", CursorStatus.SUSPENDED.name()));
        return (int) result.getModifiedCount();
    }

    @Override
    public int resumeByKnowledge(String knowledgeId) {
        var result = collection().updateMany(
                and(eq("knowledgeId", knowledgeId),
                        eq("status", CursorStatus.SUSPENDED.name())),
                Updates.set("status", CursorStatus.AVAILABLE.name()));
        return (int) result.getModifiedCount();
    }

    @Override
    public boolean retire(String cursorId) {
        // Skip a cursor a worker is mid-run on; the next reconcile pass retires it once it rests.
        var result = collection().updateOne(
                and(eq("_id", cursorId), ne("status", CursorStatus.IN_PROGRESS.name())),
                Updates.combine(
                        Updates.set("status", CursorStatus.RETIRED.name()),
                        Updates.unset("lease")));
        return result.getModifiedCount() > 0;
    }

    @Override
    public boolean revive(String cursorId, Map<String, Object> attributes) {
        var result = collection().updateOne(
                and(eq("_id", cursorId), eq("status", CursorStatus.RETIRED.name())),
                Updates.combine(
                        Updates.set("status", CursorStatus.AVAILABLE.name()),
                        Updates.set("attributes", BsonSupport.toBsonMap(attributes)),
                        Updates.set("position", new Document()),
                        Updates.set("retry", new Document("count", 0)),
                        Updates.unset("lease")));
        return result.getModifiedCount() > 0;
    }

    @Override
    public boolean resetToStart(String cursorId) {
        // Skip a cursor mid-run; its live lease would otherwise be clobbered. Attributes/stats kept.
        var result = collection().updateOne(
                and(eq("_id", cursorId), ne("status", CursorStatus.IN_PROGRESS.name())),
                Updates.combine(
                        Updates.set("status", CursorStatus.AVAILABLE.name()),
                        Updates.set("position", new Document()),
                        Updates.set("retry", new Document("count", 0).append("lastError", null)),
                        Updates.unset("lease")));
        return result.getModifiedCount() > 0;
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        collection().deleteMany(eq("knowledgeId", knowledgeId));
    }

    /** Claimable = {@code AVAILABLE}, or {@code IN_PROGRESS} whose lease has expired (crash recovery). */
    private static Bson claimableFilter(Instant now) {
        return or(
                eq("status", CursorStatus.AVAILABLE.name()),
                and(eq("status", CursorStatus.IN_PROGRESS.name()),
                        lt("lease.expiresAt", BsonSupport.date(now))));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Cursor c) {
        Document lease = c.lease() == null ? null
                : new Document("owner", c.lease().owner()).append("expiresAt", BsonSupport.date(c.lease().expiresAt()));
        return new Document("_id", c.id())
                .append("knowledgeId", c.knowledgeId())
                .append("iterableId", c.iterableId())
                .append("attributes", BsonSupport.toBsonMap(c.attributes()))
                .append("direction", BsonSupport.enumName(c.direction()))
                .append("position", c.position() == null ? null : BsonSupport.toBsonMap(c.position().values()))
                .append("status", BsonSupport.enumName(c.status()))
                .append("lease", lease)
                .append("retry", new Document("count", c.retry().count())
                        .append("lastError", c.retry().lastError()))
                .append("stats", new Document("lastRunAt", BsonSupport.date(c.stats().lastRunAt()))
                        .append("fetched", c.stats().fetched()))
                .append("scope", new Document("connectorType", BsonSupport.enumName(c.scope().connectorType())));
    }

    private Cursor fromDoc(Document d) {
        Document lease = BsonSupport.sub(d, "lease");
        Document retry = BsonSupport.sub(d, "retry");
        Document stats = BsonSupport.sub(d, "stats");
        Document scope = BsonSupport.sub(d, "scope");
        return new Cursor(
                d.getString("_id"),
                d.getString("knowledgeId"),
                d.getString("iterableId"),
                BsonSupport.toPlainMap(d.get("attributes")),
                BsonSupport.enumOf(CursorDirection.class, d.get("direction")),
                CursorPosition.of(BsonSupport.toPlainMap(d.get("position"))),
                BsonSupport.enumOf(CursorStatus.class, d.get("status")),
                lease == null ? null : new Cursor.Lease(lease.getString("owner"),
                        BsonSupport.instant(lease.get("expiresAt"))),
                new Cursor.Retry(retry == null ? 0 : intValue(retry.get("count")),
                        retry == null ? null : retry.getString("lastError")),
                new Cursor.Stats(stats == null ? null : BsonSupport.instant(stats.get("lastRunAt")),
                        stats == null ? 0 : longValue(stats.get("fetched"))),
                new Cursor.Scope(scope == null ? null
                        : BsonSupport.enumOf(SourceType.class, scope.get("connectorType"))));
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
