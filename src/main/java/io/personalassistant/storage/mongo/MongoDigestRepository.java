package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.storage.repository.DigestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** MongoDB adapter for {@link DigestRepository} over the {@code digests} and {@code digestRuns} collections. */
@ApplicationScoped
public class MongoDigestRepository implements DigestRepository {

    static final String COLLECTION = "digests";
    static final String RUNS_COLLECTION = "digestRuns";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoDigestRepository(MongoClient mongoClient,
                                 @ConfigProperty(name = "quarkus.mongodb.database",
                                         defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    private MongoCollection<Document> runs() {
        return mongoClient.getDatabase(database).getCollection(RUNS_COLLECTION);
    }

    @Override
    public Digest save(Digest digest) {
        collection().replaceOne(eq("_id", digest.id()), toDoc(digest), new ReplaceOptions().upsert(true));
        return digest;
    }

    @Override
    public Optional<Digest> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Digest> findAll() {
        List<Digest> out = new ArrayList<>();
        collection().find().sort(descending("createdAt")).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Digest> findDue(Instant now, int limit) {
        // A null nextRunAt means "never run, due now" — a freshly created digest must not wait a whole
        // interval before its first run.
        var filter = and(eq("enabled", true),
                or(eq("nextRunAt", null), lte("nextRunAt", BsonSupport.date(now))));
        List<Digest> out = new ArrayList<>();
        collection().find(filter).limit(limit).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
        deleteRuns(id);
    }

    @Override
    public DigestRun saveRun(DigestRun run) {
        runs().replaceOne(eq("_id", run.id()), toRunDoc(run), new ReplaceOptions().upsert(true));
        return run;
    }

    @Override
    public List<DigestRun> findRuns(String digestId, int limit) {
        return findRuns(digestId, limit, 0);
    }

    @Override
    public List<DigestRun> findRuns(String digestId, int limit, int offset) {
        List<DigestRun> out = new ArrayList<>();
        runs().find(eq("digestId", digestId)).sort(descending("ranAt"))
                .skip(Math.max(offset, 0)).limit(limit)
                .forEach(d -> out.add(fromRunDoc(d)));
        return out;
    }

    @Override
    public Optional<DigestRun> findRun(String runId) {
        return Optional.ofNullable(runs().find(eq("_id", runId)).first()).map(this::fromRunDoc);
    }

    @Override
    public Optional<DigestRun> findLatestRun(String digestId) {
        return Optional.ofNullable(
                        runs().find(eq("digestId", digestId)).sort(descending("ranAt")).first())
                .map(this::fromRunDoc);
    }

    @Override
    public Set<String> reportedEntityIds(String digestId) {
        return reportedEntityIds(digestId, null);
    }

    @Override
    public Set<String> reportedEntityIds(String digestId, Instant since) {
        // Projected to the ids alone: a run document also carries titles and snippets, and the whole
        // history is read on every run, so paging the full documents back would grow with the digest's
        // age for data this query does not use.
        Set<String> out = new LinkedHashSet<>();
        // The (digestId, ranAt) index already covers the bounded form, so a reset costs nothing.
        var filter = since == null ? eq("digestId", digestId)
                : and(eq("digestId", digestId), gte("ranAt", BsonSupport.date(since)));
        runs().find(filter)
                .projection(Projections.include("items.entityId"))
                .forEach(d -> {
                    Object items = d.get("items");
                    if (items instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Document doc) {
                                String entityId = doc.getString("entityId");
                                if (entityId != null) {
                                    out.add(entityId);
                                }
                            }
                        }
                    }
                });
        return out;
    }

    @Override
    public void deleteRuns(String digestId) {
        runs().deleteMany(eq("digestId", digestId));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Digest d) {
        return new Document("_id", d.id())
                .append("name", d.name())
                .append("query", d.query())
                .append("sourceEntityId", d.sourceEntityId())
                .append("knowledgeIds", d.knowledgeIds())
                .append("filters", BsonSupport.toBsonMap(d.filters()))
                .append("window", d.window())
                .append("schedule", new Document("cron", d.schedule().cron())
                        .append("interval", d.schedule().interval() == null
                                ? null : d.schedule().interval().toString()))
                .append("taskId", d.taskId())
                .append("topK", d.topK())
                .append("collapseDuplicates", d.collapseDuplicates())
                .append("maxChunksPerEntity", d.maxChunksPerEntity())
                .append("onlyNew", d.onlyNew())
                .append("enabled", d.enabled())
                .append("nextRunAt", BsonSupport.date(d.nextRunAt()))
                .append("createdAt", BsonSupport.date(d.createdAt()))
                .append("updatedAt", BsonSupport.date(d.updatedAt()))
                .append("historyResetAt", BsonSupport.date(d.historyResetAt()));
    }

    private Digest fromDoc(Document d) {
        Document schedule = BsonSupport.sub(d, "schedule");
        String interval = schedule == null ? null : schedule.getString("interval");
        String cron = schedule == null ? null : schedule.getString("cron");
        return new Digest(
                d.getString("_id"),
                d.getString("name"),
                d.getString("query"),
                d.getString("sourceEntityId"),
                stringList(d.get("knowledgeIds")),
                BsonSupport.toPlainMap(d.get("filters")),
                d.getString("window"),
                cron != null ? SyncSchedule.ofCron(cron)
                        : interval == null ? SyncSchedule.NONE
                                : SyncSchedule.ofInterval(java.time.Duration.parse(interval)),
                d.getString("taskId"),
                intValue(d.get("topK")),
                Boolean.TRUE.equals(d.getBoolean("collapseDuplicates")),
                d.get("maxChunksPerEntity") instanceof Number n ? n.intValue() : null,
                Boolean.TRUE.equals(d.getBoolean("onlyNew")),
                Boolean.TRUE.equals(d.getBoolean("enabled")),
                BsonSupport.instant(d.get("nextRunAt")),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")),
                BsonSupport.instant(d.get("historyResetAt")));
    }

    private Document toRunDoc(DigestRun run) {
        List<Document> items = new ArrayList<>();
        for (DigestRun.Item item : run.items()) {
            items.add(new Document("entityId", item.entityId())
                    .append("chunkId", item.chunkId())
                    .append("title", item.title())
                    .append("uri", item.uri())
                    .append("score", item.score())
                    .append("snippet", item.snippet())
                    .append("annotations", BsonSupport.toBsonMap(item.annotations())));
        }
        return new Document("_id", run.id())
                .append("digestId", run.digestId())
                .append("ranAt", BsonSupport.date(run.ranAt()))
                .append("items", items)
                .append("taskOutput", run.taskOutput())
                .append("candidates", run.candidates())
                .append("suppressed", run.suppressed())
                .append("outsideWindow", run.outsideWindow())
                .append("error", run.error());
    }

    private DigestRun fromRunDoc(Document d) {
        List<DigestRun.Item> items = new ArrayList<>();
        if (d.get("items") instanceof List<?> raw) {
            for (Object o : raw) {
                if (o instanceof Document item) {
                    items.add(new DigestRun.Item(
                            item.getString("entityId"),
                            item.getString("chunkId"),
                            item.getString("title"),
                            item.getString("uri"),
                            item.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                            item.getString("snippet"),
                            // Absent on every run written before annotations existed.
                            BsonSupport.toPlainMap(item.get("annotations"))));
                }
            }
        }
        return new DigestRun(
                d.getString("_id"),
                d.getString("digestId"),
                BsonSupport.instant(d.get("ranAt")),
                items,
                d.getString("taskOutput"),
                // Runs recorded before the counters existed report what they can: the items they kept.
                d.get("candidates") instanceof Number n ? n.intValue() : items.size(),
                d.get("suppressed") instanceof Number n ? n.intValue() : 0,
                // Absent on every run written before the window diagnostic existed.
                d.get("outsideWindow") instanceof Number n ? n.intValue() : 0,
                d.getString("error"));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return List.copyOf(out);
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
