package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.storage.repository.DeliveryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** MongoDB adapter for {@link DeliveryRepository} over the {@code deliveries} collection. */
@ApplicationScoped
public class MongoDeliveryRepository implements DeliveryRepository {

    static final String COLLECTION = "deliveries";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoDeliveryRepository(MongoClient mongoClient,
                                   @ConfigProperty(name = "quarkus.mongodb.database",
                                           defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Delivery insertIfAbsent(Delivery delivery) {
        try {
            collection().insertOne(toDoc(delivery));
            return delivery;
        } catch (MongoWriteException e) {
            // The unique partial index on dedupeKey is the arbiter, so a concurrent enqueue of the same
            // key loses here rather than in a read-then-write race.
            if (delivery.dedupeKey() != null
                    && ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                Document existing = collection().find(eq("dedupeKey", delivery.dedupeKey())).first();
                if (existing != null) {
                    return fromDoc(existing);
                }
            }
            throw e;
        }
    }

    @Override
    public Optional<Delivery> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Delivery> find(String channelId, String refId, DeliveryStatus status, int limit, int offset) {
        List<Bson> clauses = new ArrayList<>();
        if (channelId != null && !channelId.isBlank()) {
            clauses.add(eq("channelId", channelId));
        }
        if (refId != null && !refId.isBlank()) {
            clauses.add(eq("origin.refId", refId));
        }
        if (status != null) {
            clauses.add(eq("status", status.name()));
        }
        Bson filter = clauses.isEmpty() ? new Document() : and(clauses);
        List<Delivery> out = new ArrayList<>();
        collection().find(filter).sort(descending("createdAt"))
                .skip(Math.max(offset, 0)).limit(Math.max(limit, 1))
                .forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public Optional<Delivery> claimNext(Collection<String> channelIds, String owner, Duration lease,
                                        Instant now) {
        if (channelIds == null || channelIds.isEmpty()) {
            return Optional.empty();
        }
        Bson filter = and(
                eq("status", DeliveryStatus.PENDING.name()),
                in("channelId", channelIds),
                or(eq("nextAttemptAt", null), lte("nextAttemptAt", BsonSupport.date(now))),
                or(eq("lease", null), lt("lease.expiresAt", BsonSupport.date(now))));
        Bson update = Updates.set("lease", leaseDoc(owner, now.plus(lease)));
        Document claimed = collection().findOneAndUpdate(filter, update, new FindOneAndUpdateOptions()
                .sort(ascending("createdAt"))
                .returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(claimed).map(this::fromDoc);
    }

    @Override
    public boolean markSent(String id, String owner, String providerMessageId, Instant sentAt) {
        return collection().updateOne(ownedBy(id, owner), Updates.combine(
                Updates.set("status", DeliveryStatus.SENT.name()),
                Updates.set("providerMessageId", providerMessageId),
                Updates.set("sentAt", BsonSupport.date(sentAt)),
                // Success ends the streak: attempts are consecutive failures (invariant 9).
                Updates.set("attempts", 0),
                Updates.set("lastError", null),
                Updates.set("nextAttemptAt", null),
                Updates.unset("lease"))).getMatchedCount() > 0;
    }

    @Override
    public boolean markRetry(String id, String owner, String error, int attempts, Instant nextAttemptAt) {
        return collection().updateOne(ownedBy(id, owner), Updates.combine(
                Updates.set("attempts", attempts),
                Updates.set("lastError", error),
                Updates.set("nextAttemptAt", BsonSupport.date(nextAttemptAt)),
                Updates.unset("lease"))).getMatchedCount() > 0;
    }

    @Override
    public boolean markFailed(String id, String owner, String error, int attempts) {
        return collection().updateOne(ownedBy(id, owner), Updates.combine(
                Updates.set("status", DeliveryStatus.FAILED.name()),
                Updates.set("attempts", attempts),
                Updates.set("lastError", error),
                Updates.set("nextAttemptAt", null),
                Updates.unset("lease"))).getMatchedCount() > 0;
    }

    @Override
    public boolean release(String id, String owner) {
        return collection().updateOne(ownedBy(id, owner), Updates.unset("lease")).getMatchedCount() > 0;
    }

    @Override
    public boolean requeue(String id, Instant at) {
        return collection().updateOne(
                and(eq("_id", id), eq("status", DeliveryStatus.FAILED.name())),
                Updates.combine(
                        Updates.set("status", DeliveryStatus.PENDING.name()),
                        Updates.set("attempts", 0),
                        Updates.set("nextAttemptAt", null),
                        Updates.unset("lease"))).getMatchedCount() > 0;
    }

    @Override
    public void deleteByChannel(String channelId) {
        collection().deleteMany(eq("channelId", channelId));
    }

    /** Lease fence: the delivery, only while {@code owner} still holds a live lease on it. */
    private static Bson ownedBy(String id, String owner) {
        return and(eq("_id", id), eq("lease.owner", owner),
                gt("lease.expiresAt", BsonSupport.date(Instant.now())));
    }

    private static Document leaseDoc(String owner, Instant expiresAt) {
        return new Document("owner", owner).append("expiresAt", BsonSupport.date(expiresAt));
    }

    private Document toDoc(Delivery d) {
        return new Document("_id", d.id())
                .append("channelId", d.channelId())
                .append("origin", new Document("kind", d.origin().kind())
                        .append("refId", d.origin().refId()))
                .append("dedupeKey", d.dedupeKey())
                .append("message", messageDoc(d.message()))
                .append("status", BsonSupport.enumName(d.status()))
                .append("attempts", d.attempts())
                .append("nextAttemptAt", BsonSupport.date(d.nextAttemptAt()))
                .append("lease", d.lease() == null ? null
                        : leaseDoc(d.lease().owner(), d.lease().expiresAt()))
                .append("lastError", d.lastError())
                .append("providerMessageId", d.providerMessageId())
                .append("createdAt", BsonSupport.date(d.createdAt()))
                .append("sentAt", BsonSupport.date(d.sentAt()));
    }

    private Delivery fromDoc(Document d) {
        Document origin = BsonSupport.sub(d, "origin");
        Document lease = BsonSupport.sub(d, "lease");
        return new Delivery(
                d.getString("_id"),
                d.getString("channelId"),
                origin == null ? null
                        : new Delivery.Origin(origin.getString("kind"), origin.getString("refId")),
                d.getString("dedupeKey"),
                message(BsonSupport.sub(d, "message")),
                BsonSupport.enumOf(DeliveryStatus.class, d.get("status")),
                d.getInteger("attempts", 0),
                BsonSupport.instant(d.get("nextAttemptAt")),
                lease == null ? null
                        : new Delivery.Lease(lease.getString("owner"),
                                BsonSupport.instant(lease.get("expiresAt"))),
                d.getString("lastError"),
                d.getString("providerMessageId"),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("sentAt")));
    }

    private static Document messageDoc(PublishMessage m) {
        if (m == null) {
            return null;
        }
        List<Document> items = new ArrayList<>(m.items().size());
        for (PublishMessage.Item item : m.items()) {
            items.add(new Document("title", item.title())
                    .append("uri", item.uri())
                    .append("text", item.text())
                    .append("fields", BsonSupport.toBsonMap(item.fields())));
        }
        return new Document("title", m.title())
                .append("intro", m.intro())
                .append("items", items)
                .append("link", m.link())
                .append("summary", m.summary());
    }

    private static PublishMessage message(Document d) {
        if (d == null) {
            return new PublishMessage(null, null, List.of(), null);
        }
        List<PublishMessage.Item> items = new ArrayList<>();
        if (d.get("items") instanceof List<?> raw) {
            for (Object entry : raw) {
                if (entry instanceof Document item) {
                    items.add(new PublishMessage.Item(item.getString("title"), item.getString("uri"),
                            item.getString("text"), BsonSupport.toPlainMap(item.get("fields"))));
                }
            }
        }
        return new PublishMessage(d.getString("title"), d.getString("intro"), items,
                d.getString("link"), d.getString("summary"));
    }
}
