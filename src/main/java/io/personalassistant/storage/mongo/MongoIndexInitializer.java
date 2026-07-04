package io.personalassistant.storage.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Ensures the Mongo indexes the ingestion/indexing pipeline depends on exist at startup. Index
 * creation is idempotent, so this is safe to run on every boot. The unique
 * {@code (knowledgeId, externalId)} index on {@code entities} is what makes upserts dedupe.
 */
@Singleton
public class MongoIndexInitializer {

    private static final Logger LOG = Logger.getLogger(MongoIndexInitializer.class.getName());

    private final MongoClient mongoClient;
    private final String database;

    @Inject
    public MongoIndexInitializer(MongoClient mongoClient,
                                 @ConfigProperty(name = "quarkus.mongodb.database",
                                         defaultValue = "personal_assistant") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    void onStart(@Observes StartupEvent event) {
        MongoDatabase db = mongoClient.getDatabase(database);

        db.getCollection(MongoKnowledgeRepository.COLLECTION)
                .createIndex(Indexes.ascending("status"));
        db.getCollection(MongoKnowledgeRepository.COLLECTION)
                .createIndex(Indexes.ascending("connectorDetails.type"));
        db.getCollection(MongoKnowledgeRepository.COLLECTION)
                .createIndex(Indexes.ascending("connectorDetails.connectionId"));

        db.getCollection(MongoConnectionRepository.COLLECTION)
                .createIndex(Indexes.ascending("type"));
        db.getCollection(MongoConnectionRepository.COLLECTION)
                .createIndex(Indexes.ascending("type", "isDefault"));

        db.getCollection(MongoCursorRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId"));
        db.getCollection(MongoCursorRepository.COLLECTION)
                .createIndex(Indexes.ascending("status"));
        db.getCollection(MongoCursorRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId", "direction", "status"));

        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId", "externalId"), new IndexOptions().unique(true));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("status"));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("needsReindex"));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("retry.nextAttemptAt"));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId", "status"));

        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId"));
        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("direction"));
        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("lastOutcome"));

        LOG.info("Mongo indexes ensured for database '" + database + "'");
    }
}
