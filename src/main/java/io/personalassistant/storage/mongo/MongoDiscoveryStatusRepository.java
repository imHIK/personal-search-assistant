package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.DiscoveryOutcome;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import io.personalassistant.storage.repository.DiscoveryStatusRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MongoDB adapter for {@link DiscoveryStatusRepository} over the {@code discovery} collection. One
 * document per {@code (knowledgeId, direction)} — the backward and forward grabbers each get their own.
 *
 * <p>{@link #record} is an atomic {@code upsert} with {@code $inc} counters, so concurrent or
 * repeated runs accumulate {@code runCount}/{@code failureCount} correctly without a read-modify-write
 * race. On a {@code FAILED} run the {@code iterablesFound}/{@code lastCounts} fields are deliberately
 * left untouched, so a failure never overwrites the last known-good snapshot.
 */
@ApplicationScoped
public class MongoDiscoveryStatusRepository implements DiscoveryStatusRepository {

    static final String COLLECTION = "discovery";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoDiscoveryStatusRepository(MongoClient mongoClient,
                                          @ConfigProperty(name = "quarkus.mongodb.database",
                                                  defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public void record(DiscoveryStatus.Run run) {
        String id = Ids.discoveryFor(run.knowledgeId(), run.direction().name());
        Instant now = run.ranAt();
        boolean ok = run.outcome() == DiscoveryOutcome.OK;

        List<Bson> updates = new ArrayList<>(List.of(
                // _id comes from the filter on insert; immutable identity fields set only on insert.
                Updates.setOnInsert("knowledgeId", run.knowledgeId()),
                Updates.setOnInsert("direction", BsonSupport.enumName(run.direction())),
                Updates.setOnInsert("createdAt", BsonSupport.date(now)),
                Updates.set("lastOutcome", BsonSupport.enumName(run.outcome())),
                Updates.set("lastTrigger", BsonSupport.enumName(run.trigger())),
                Updates.set("lastRunAt", BsonSupport.date(now)),
                Updates.set("lastError", run.error()),
                Updates.set("updatedAt", BsonSupport.date(now)),
                Updates.inc("runCount", 1L),
                Updates.inc("failureCount", ok ? 0L : 1L)));

        if (ok) {
            DiscoveryStatus.Counts c = run.counts();
            updates.add(Updates.set("iterablesFound", run.iterablesFound()));
            updates.add(Updates.set("lastCounts", new Document("created", c.created())
                    .append("revived", c.revived())
                    .append("retired", c.retired())));
        }

        collection().updateOne(eq("_id", id), Updates.combine(updates),
                new UpdateOptions().upsert(true));
    }

    @Override
    public Optional<DiscoveryStatus> find(String knowledgeId, CursorDirection direction) {
        String id = Ids.discoveryFor(knowledgeId, direction.name());
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<DiscoveryStatus> findByKnowledge(String knowledgeId) {
        List<DiscoveryStatus> out = new ArrayList<>();
        collection().find(eq("knowledgeId", knowledgeId)).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        collection().deleteMany(eq("knowledgeId", knowledgeId));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private DiscoveryStatus fromDoc(Document d) {
        Document counts = BsonSupport.sub(d, "lastCounts");
        return new DiscoveryStatus(
                d.getString("_id"),
                d.getString("knowledgeId"),
                BsonSupport.enumOf(CursorDirection.class, d.get("direction")),
                BsonSupport.enumOf(DiscoveryOutcome.class, d.get("lastOutcome")),
                BsonSupport.enumOf(DiscoveryTrigger.class, d.get("lastTrigger")),
                BsonSupport.instant(d.get("lastRunAt")),
                intValue(d.get("iterablesFound")),
                counts == null ? DiscoveryStatus.Counts.zero()
                        : new DiscoveryStatus.Counts(intValue(counts.get("created")),
                        intValue(counts.get("revived")), intValue(counts.get("retired"))),
                longValue(d.get("runCount")),
                longValue(d.get("failureCount")),
                d.getString("lastError"),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
