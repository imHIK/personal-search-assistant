package io.personalassistant.storage.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.logging.Logger;
import org.bson.BsonType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Idempotent, so it runs on every boot. The unique (knowledgeId, externalId) index on entities is what makes
 * upserts dedupe.
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
        // Rate-limit holds: the claim filter reads this on every poll tick.
        db.getCollection(MongoCursorRepository.COLLECTION)
                .createIndex(Indexes.ascending("retry.nextAttemptAt"));

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
        // The console's sorted entity listing; _id is the paging tiebreak. The (knowledgeId, status) index is
        // now a redundant prefix, but there is no drop path.
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.compoundIndex(Indexes.ascending("knowledgeId"),
                        Indexes.descending("updatedAt"), Indexes.ascending("_id")));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.compoundIndex(Indexes.ascending("knowledgeId", "status"),
                        Indexes.descending("updatedAt"), Indexes.ascending("_id")));
        // The listing narrowed to one group, which otherwise walks every entity of a large source.
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.compoundIndex(Indexes.ascending("knowledgeId", "iterableId"),
                        Indexes.descending("updatedAt"), Indexes.ascending("_id")));

        // Source-declared expiry is a global scan; the window pass is always scoped to one knowledge.
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("expiresAt"));
        db.getCollection(MongoEntityRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId", "createdAt"));

        // Digests: the scheduler's due query, and a digest's run history newest-first.
        db.getCollection(MongoDigestRepository.COLLECTION)
                .createIndex(Indexes.ascending("enabled", "nextRunAt"));
        // Deleting a channel is refused while a digest sends to it.
        db.getCollection(MongoDigestRepository.COLLECTION)
                .createIndex(Indexes.ascending("channelIds"));
        db.getCollection(MongoDigestRepository.RUNS_COLLECTION)
                .createIndex(Indexes.compoundIndex(Indexes.ascending("digestId"),
                        Indexes.descending("ranAt")));

        // The worker's claim query and the histories. dedupeKey is unique only where set, so manual sends
        // never collide.
        db.getCollection(MongoChannelRepository.COLLECTION)
                .createIndex(Indexes.ascending("enabled", "status"));
        // Deleting a connection is refused while a channel sends through it.
        db.getCollection(MongoChannelRepository.COLLECTION)
                .createIndex(Indexes.ascending("connectionId"));
        db.getCollection(MongoDeliveryRepository.COLLECTION)
                .createIndex(Indexes.ascending("status", "channelId", "nextAttemptAt"));
        db.getCollection(MongoDeliveryRepository.COLLECTION)
                .createIndex(Indexes.compoundIndex(Indexes.ascending("channelId"),
                        Indexes.descending("createdAt")));
        db.getCollection(MongoDeliveryRepository.COLLECTION)
                .createIndex(Indexes.descending("createdAt"));
        // A digest run's deliveries, for the "sent to" line under each run.
        db.getCollection(MongoDeliveryRepository.COLLECTION)
                .createIndex(Indexes.ascending("origin.refId"));
        db.getCollection(MongoDeliveryRepository.COLLECTION)
                .createIndex(Indexes.ascending("dedupeKey"), new IndexOptions().unique(true)
                        .partialFilterExpression(Filters.type("dedupeKey", BsonType.STRING)));

        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("knowledgeId"));
        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("direction"));
        db.getCollection(MongoDiscoveryStatusRepository.COLLECTION)
                .createIndex(Indexes.ascending("lastOutcome"));

        LOG.info("Mongo indexes ensured for database '" + database + "'");
    }
}
