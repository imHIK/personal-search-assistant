package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** MongoDB adapter for {@link ConnectionRepository} over the {@code connections} collection. */
@ApplicationScoped
public class MongoConnectionRepository implements ConnectionRepository {

    static final String COLLECTION = "connections";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoConnectionRepository(MongoClient mongoClient,
                                     @ConfigProperty(name = "quarkus.mongodb.database",
                                             defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Connection save(Connection connection) {
        collection().replaceOne(eq("_id", connection.id()), toDoc(connection),
                new ReplaceOptions().upsert(true));
        return connection;
    }

    @Override
    public Optional<Connection> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Connection> findAll() {
        List<Connection> out = new ArrayList<>();
        collection().find().forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Connection> findByType(SourceType type) {
        List<Connection> out = new ArrayList<>();
        collection().find(eq("type", type.name())).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public Optional<Connection> findDefault(SourceType type) {
        return Optional.ofNullable(
                        collection().find(and(eq("type", type.name()), eq("isDefault", true))).first())
                .map(this::fromDoc);
    }

    @Override
    public void clearDefault(SourceType type) {
        collection().updateMany(and(eq("type", type.name()), eq("isDefault", true)),
                Updates.set("isDefault", false));
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Connection c) {
        return new Document("_id", c.id())
                .append("name", c.name())
                .append("type", BsonSupport.enumName(c.type()))
                .append("auth", BsonSupport.toBsonMap(c.auth()))
                .append("config", BsonSupport.toBsonMap(c.config()))
                .append("isDefault", c.isDefault())
                .append("status", BsonSupport.enumName(c.status()))
                .append("lastError", c.lastError())
                .append("createdAt", BsonSupport.date(c.createdAt()))
                .append("updatedAt", BsonSupport.date(c.updatedAt()));
    }

    private Connection fromDoc(Document d) {
        return new Connection(
                d.getString("_id"),
                d.getString("name"),
                BsonSupport.enumOf(SourceType.class, d.get("type")),
                BsonSupport.toPlainMap(d.get("auth")),
                BsonSupport.toPlainMap(d.get("config")),
                Boolean.TRUE.equals(d.getBoolean("isDefault")),
                BsonSupport.enumOf(ConnectionStatus.class, d.get("status")),
                d.getString("lastError"),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }
}
