package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.storage.repository.KnowledgeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** MongoDB adapter for {@link KnowledgeRepository} over the {@code knowledge} collection. */
@ApplicationScoped
public class MongoKnowledgeRepository implements KnowledgeRepository {

    static final String COLLECTION = "knowledge";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoKnowledgeRepository(MongoClient mongoClient,
                                    @ConfigProperty(name = "quarkus.mongodb.database",
                                            defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Knowledge save(Knowledge knowledge) {
        collection().replaceOne(eq("_id", knowledge.id()), toDoc(knowledge),
                new ReplaceOptions().upsert(true));
        return knowledge;
    }

    @Override
    public Optional<Knowledge> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Knowledge> findAll() {
        List<Knowledge> out = new ArrayList<>();
        collection().find().forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Knowledge> findByStatus(KnowledgeStatus status) {
        List<Knowledge> out = new ArrayList<>();
        collection().find(eq("status", status.name())).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public void updateStatus(String id, KnowledgeStatus status) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", status.name()),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void markError(String id, String lastError) {
        collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", KnowledgeStatus.ERROR.name()),
                Updates.set("lastError", lastError),
                Updates.set("updatedAt", BsonSupport.date(Instant.now()))));
    }

    @Override
    public void updateNextSyncDueAt(String id, Instant nextDueAt) {
        // Scheduler bookkeeping only — deliberately does NOT touch updatedAt.
        collection().updateOne(eq("_id", id),
                Updates.set("nextSyncDueAt", BsonSupport.date(nextDueAt)));
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Knowledge k) {
        Knowledge.ConnectorDetails cd = k.connectorDetails();
        Knowledge.Config cfg = k.config();
        return new Document("_id", k.id())
                .append("name", k.name())
                .append("connectorDetails", new Document("type", BsonSupport.enumName(cd.type()))
                        .append("auth", BsonSupport.toBsonMap(cd.auth())))
                .append("inputs", BsonSupport.toBsonMap(k.inputs()))
                .append("config", new Document()
                        .append("scheduleSettings", new Document("cron", cfg.scheduleSettings().cron())
                                .append("interval", cfg.scheduleSettings().interval())
                                .append("enabled", cfg.scheduleSettings().enabled()))
                        .append("webhookSettings", new Document("enabled", cfg.webhookSettings().enabled())
                                .append("secret", cfg.webhookSettings().secret()))
                        .append("backfill", new Document("enabled", cfg.backfill().enabled())))
                .append("anchor", BsonSupport.date(k.anchor()))
                .append("nextSyncDueAt", BsonSupport.date(k.nextSyncDueAt()))
                .append("status", BsonSupport.enumName(k.status()))
                .append("lastError", k.lastError())
                .append("stats", new Document("entities", k.stats().entities())
                        .append("indexed", k.stats().indexed())
                        .append("failed", k.stats().failed()))
                .append("createdAt", BsonSupport.date(k.createdAt()))
                .append("updatedAt", BsonSupport.date(k.updatedAt()));
    }

    private Knowledge fromDoc(Document d) {
        Document cd = BsonSupport.sub(d, "connectorDetails");
        Document cfg = BsonSupport.sub(d, "config");
        Document sched = BsonSupport.sub(cfg, "scheduleSettings");
        Document hook = BsonSupport.sub(cfg, "webhookSettings");
        Document back = BsonSupport.sub(cfg, "backfill");
        Document stats = BsonSupport.sub(d, "stats");
        return new Knowledge(
                d.getString("_id"),
                d.getString("name"),
                new Knowledge.ConnectorDetails(
                        BsonSupport.enumOf(SourceType.class, cd == null ? null : cd.get("type")),
                        cd == null ? java.util.Map.of() : BsonSupport.toPlainMap(cd.get("auth"))),
                BsonSupport.toPlainMap(d.get("inputs")),
                new Knowledge.Config(
                        new Knowledge.ScheduleSettings(
                                sched == null ? null : sched.getString("cron"),
                                sched == null ? null : sched.getString("interval"),
                                sched != null && Boolean.TRUE.equals(sched.getBoolean("enabled"))),
                        new Knowledge.WebhookSettings(
                                hook != null && Boolean.TRUE.equals(hook.getBoolean("enabled")),
                                hook == null ? null : hook.getString("secret")),
                        new Knowledge.Backfill(back != null && Boolean.TRUE.equals(back.getBoolean("enabled")))),
                BsonSupport.instant(d.get("anchor")),
                BsonSupport.instant(d.get("nextSyncDueAt")),
                BsonSupport.enumOf(KnowledgeStatus.class, d.get("status")),
                d.getString("lastError"),
                stats == null ? Knowledge.Stats.zero() : new Knowledge.Stats(
                        longValue(stats.get("entities")),
                        longValue(stats.get("indexed")),
                        longValue(stats.get("failed"))),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
