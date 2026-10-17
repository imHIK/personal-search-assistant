package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import io.personalassistant.storage.repository.WatchedCompanyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MongoWatchedCompanyRepository implements WatchedCompanyRepository {

    static final String COLLECTION = "watched_companies";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoWatchedCompanyRepository(MongoClient mongoClient,
                                         @ConfigProperty(name = "quarkus.mongodb.database",
                                                 defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public WatchedCompany insert(WatchedCompany company) {
        uniqueName(company.name(), () -> collection().insertOne(toDoc(company)));
        return company;
    }

    @Override
    public Optional<WatchedCompany> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public Optional<WatchedCompany> findByName(String name) {
        return Optional.ofNullable(collection().find(eq("nameKey", WatchedCompany.nameKey(name))).first())
                .map(this::fromDoc);
    }

    @Override
    public List<WatchedCompany> findAll() {
        List<WatchedCompany> out = new ArrayList<>();
        collection().find().sort(ascending("nameKey")).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public boolean updateEdits(String id, String name, String domain, String careersUrl, String platform,
                               CompanyTier tier, String reason, String note, String lookupHandle, Instant at) {
        return uniqueName(name, () -> collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("name", name),
                Updates.set("nameKey", WatchedCompany.nameKey(name)),
                Updates.set("domain", domain),
                Updates.set("careersUrl", careersUrl),
                Updates.set("platform", platform),
                Updates.set("tier", name(tier)),
                Updates.set("reason", reason),
                Updates.set("note", note),
                Updates.set("lookupHandle", lookupHandle),
                Updates.set("updatedAt", BsonSupport.date(at)))).getMatchedCount() > 0);
    }

    @Override
    public boolean fillTier(String id, CompanyTier tier) {
        // eq null also matches a document with no tier field at all.
        return collection().updateOne(and(eq("_id", id), eq("tier", null)), Updates.set("tier", tier.name()))
                .getModifiedCount() > 0;
    }

    @Override
    public boolean updateLastCheckedAt(String id, Instant checkedAt) {
        return collection().updateOne(eq("_id", id), Updates.set("lastCheckedAt", BsonSupport.date(checkedAt)))
                .getMatchedCount() > 0;
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    private static <T> T uniqueName(String name, Supplier<T> write) {
        try {
            return write.get();
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new IllegalStateException("A watched company named " + name + " already exists");
            }
            throw e;
        }
    }

    private Document toDoc(WatchedCompany c) {
        return new Document("_id", c.id())
                .append("name", c.name())
                .append("nameKey", WatchedCompany.nameKey(c.name()))
                .append("domain", c.domain())
                .append("careersUrl", c.careersUrl())
                .append("platform", c.platform())
                .append("tier", name(c.tier()))
                .append("reason", c.reason())
                .append("note", c.note())
                .append("lookupHandle", c.lookupHandle())
                .append("lastCheckedAt", BsonSupport.date(c.lastCheckedAt()))
                .append("createdAt", BsonSupport.date(c.createdAt()))
                .append("updatedAt", BsonSupport.date(c.updatedAt()));
    }

    private WatchedCompany fromDoc(Document d) {
        return new WatchedCompany(
                d.getString("_id"),
                d.getString("name"),
                d.getString("domain"),
                d.getString("careersUrl"),
                d.getString("platform"),
                d.getString("tier") == null ? null : CompanyTier.valueOf(d.getString("tier")),
                d.getString("reason"),
                d.getString("note"),
                d.getString("lookupHandle"),
                BsonSupport.instant(d.get("lastCheckedAt")),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }

    private static String name(CompanyTier tier) {
        return tier == null ? null : tier.name();
    }
}
