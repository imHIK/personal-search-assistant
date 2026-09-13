package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.storage.repository.ChannelRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** MongoDB adapter for {@link ChannelRepository} over the {@code channels} collection. */
@ApplicationScoped
public class MongoChannelRepository implements ChannelRepository {

    static final String COLLECTION = "channels";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoChannelRepository(MongoClient mongoClient,
                                  @ConfigProperty(name = "quarkus.mongodb.database",
                                          defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Channel insert(Channel channel) {
        collection().insertOne(toDoc(channel));
        return channel;
    }

    @Override
    public Optional<Channel> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Channel> findAll() {
        List<Channel> out = new ArrayList<>();
        collection().find().sort(descending("createdAt")).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Channel> findUsable() {
        List<Channel> out = new ArrayList<>();
        collection().find(and(eq("enabled", true), eq("status", ChannelStatus.ACTIVE.name())))
                .forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public List<Channel> findByConnectionId(String connectionId) {
        List<Channel> out = new ArrayList<>();
        collection().find(eq("connectionId", connectionId)).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public boolean updateEdits(String id, String name, String connectionId, Map<String, Object> target,
                               boolean enabled, Instant at) {
        return collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("name", name),
                Updates.set("connectionId", connectionId),
                Updates.set("target", BsonSupport.toBsonMap(target)),
                Updates.set("enabled", enabled),
                Updates.set("updatedAt", BsonSupport.date(at)))).getMatchedCount() > 0;
    }

    @Override
    public boolean updateStatus(String id, ChannelStatus status, String lastError, Instant at) {
        return collection().updateOne(eq("_id", id), Updates.combine(
                Updates.set("status", BsonSupport.enumName(status)),
                Updates.set("lastError", status == ChannelStatus.ERROR ? lastError : null),
                Updates.set("updatedAt", BsonSupport.date(at)))).getMatchedCount() > 0;
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    private Document toDoc(Channel c) {
        return new Document("_id", c.id())
                .append("name", c.name())
                .append("type", BsonSupport.enumName(c.type()))
                .append("connectionId", c.connectionId())
                .append("target", BsonSupport.toBsonMap(c.target()))
                .append("enabled", c.enabled())
                .append("status", BsonSupport.enumName(c.status()))
                .append("lastError", c.lastError())
                .append("createdAt", BsonSupport.date(c.createdAt()))
                .append("updatedAt", BsonSupport.date(c.updatedAt()));
    }

    private Channel fromDoc(Document d) {
        return new Channel(
                d.getString("_id"),
                d.getString("name"),
                BsonSupport.enumOf(ChannelType.class, d.get("type")),
                d.getString("connectionId"),
                BsonSupport.toPlainMap(d.get("target")),
                d.getBoolean("enabled", true),
                BsonSupport.enumOf(ChannelStatus.class, d.get("status")),
                d.getString("lastError"),
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }
}
