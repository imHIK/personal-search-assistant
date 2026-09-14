package io.personalassistant.storage.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.personalassistant.domain.model.Task;
import io.personalassistant.storage.repository.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MongoDB adapter for {@link TaskRepository} over the {@code tasks} collection.
 *
 * <p>No index is declared in {@code MongoIndexInitializer}: every access is by {@code _id} or a full
 * list of what is a hand-written, human-sized collection, and both are already served.
 */
@ApplicationScoped
public class MongoTaskRepository implements TaskRepository {

    static final String COLLECTION = "tasks";

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoTaskRepository(MongoClient mongoClient,
                               @ConfigProperty(name = "quarkus.mongodb.database",
                                       defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Task save(Task task) {
        collection().replaceOne(eq("_id", task.id()), toDoc(task), new ReplaceOptions().upsert(true));
        return task;
    }

    @Override
    public Optional<Task> findById(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first()).map(this::fromDoc);
    }

    @Override
    public List<Task> findAll() {
        List<Task> out = new ArrayList<>();
        collection().find().sort(descending("createdAt")).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    @Override
    public void delete(String id) {
        collection().deleteOne(eq("_id", id));
    }

    // ---- mapping -----------------------------------------------------------------------------

    private Document toDoc(Task t) {
        List<Document> fields = new ArrayList<>();
        for (Task.Field field : t.fields()) {
            fields.add(new Document("name", field.name())
                    .append("type", BsonSupport.enumName(field.type()))
                    .append("description", field.description())
                    .append("optional", field.optional()));
        }
        return new Document("_id", t.id())
                .append("name", t.name())
                .append("description", t.description())
                .append("mode", BsonSupport.enumName(t.mode()))
                .append("instruction", t.instruction())
                .append("output", BsonSupport.enumName(t.output()))
                .append("fields", fields)
                .append("system", t.system())
                .append("user", t.user())
                .append("llmProfile", t.llmProfile())
                .append("sourceText", BsonSupport.enumName(t.sourceText()))
                .append("contextChars", t.contextChars())
                .append("maxSources", t.maxSources())
                .append("createdAt", BsonSupport.date(t.createdAt()))
                .append("updatedAt", BsonSupport.date(t.updatedAt()));
    }

    private Task fromDoc(Document d) {
        List<Task.Field> fields = new ArrayList<>();
        if (d.get("fields") instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Document f) {
                    fields.add(new Task.Field(
                            f.getString("name"),
                            BsonSupport.enumOf(Task.FieldType.class, f.get("type")),
                            f.getString("description"),
                            Boolean.TRUE.equals(f.getBoolean("optional"))));
                }
            }
        }
        return new Task(
                d.getString("_id"),
                d.getString("name"),
                d.getString("description"),
                BsonSupport.enumOf(Task.Mode.class, d.get("mode")),
                d.getString("instruction"),
                BsonSupport.enumOf(Task.Output.class, d.get("output")),
                fields,
                d.getString("system"),
                d.getString("user"),
                d.getString("llmProfile"),
                BsonSupport.enumOf(Task.SourceText.class, d.get("sourceText")),
                d.get("contextChars") instanceof Number n ? n.intValue() : 0,
                d.get("maxSources") instanceof Number n ? n.intValue() : 0,
                BsonSupport.instant(d.get("createdAt")),
                BsonSupport.instant(d.get("updatedAt")));
    }
}
