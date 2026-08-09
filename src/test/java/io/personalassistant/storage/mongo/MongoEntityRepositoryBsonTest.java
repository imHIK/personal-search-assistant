package io.personalassistant.storage.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

/**
 * Asserts the shape of the BSON the entity adapter actually emits.
 *
 * <p>Executing a real {@code Bson} predicate needs something that speaks the MongoDB query language,
 * which the suite deliberately does not have ("tests need no Mongo, OpenSearch, or network"). The
 * fakes in {@code testsupport} are therefore a <em>second implementation</em> of this logic rather
 * than a test of it — which is exactly how the missing lease fence and the cumulative retry counter
 * shipped unnoticed. These assertions are the cheap half of the gap: they pin what we wrote, so a
 * fence or a reset cannot silently disappear again.
 *
 * <p>They are not a substitute for running the predicates. Closing that properly means an in-process
 * MongoDB (mongo-java-server) behind a shared contract suite, which is tracked separately.
 */
class MongoEntityRepositoryBsonTest {

    private final MongoEntityRepository repo = new MongoEntityRepository(null, "test_db");

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    private static Entity anEntity() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Entity("ent_1", "kn_1", "root", EntityType.FILE, "ext_1", Map.of(),
                Entity.Content.ofText("body"), Map.of("title", "t"), "sha256:abc",
                EntityStatus.INGESTED, false, Entity.IndexInfo.empty(), null, Entity.Retry.zero(),
                now, now, 7L);
    }

    /** The B3 fix: ingestion writes fields, never a whole document, and drops the indexer's lease. */
    @Test
    void upsertIsFieldLevelAndDropsTheLease() {
        BsonDocument update = render(repo.upsertUpdate(anEntity()));

        BsonDocument set = update.getDocument("$set");
        assertTrue(set.containsKey("checksum"), "content fields are written");
        assertEquals("INGESTED", set.getString("status").getValue(), "the work queue is reset");
        assertFalse(set.getBoolean("needsReindex").getValue());
        assertEquals(0, set.getDocument("retry").getInt32("count").getValue());

        assertTrue(update.getDocument("$unset").containsKey("lease"),
                "dropping the lease is what fences out an indexer on the previous revision");

        // Indexer-owned rollup must survive a re-ingest: it describes what is in OpenSearch *now*.
        assertFalse(set.containsKey("index"), "must not overwrite the whole index sub-document");
        assertFalse(set.containsKey("index.chunkCount"));
        assertFalse(set.containsKey("index.embeddingModel"));
        assertTrue(set.containsKey("index.error"), "only the stale error is cleared");

        BsonDocument onInsert = update.getDocument("$setOnInsert");
        assertTrue(onInsert.containsKey("_id") && onInsert.containsKey("createdAt"),
                "identity is set on insert only, so a replay preserves it");
    }

    /** The B2 fix: three fields, not one. A filter of just {_id} is the bug. */
    @Test
    void terminalWritesAreFencedOnTheLease() {
        BsonDocument fence = render(MongoEntityRepository.ownedBy("ent_1", "worker-1"));
        BsonDocument clauses = new BsonDocument();
        fence.getArray("$and").forEach(c -> clauses.putAll(c.asDocument()));

        assertEquals("ent_1", clauses.getString("_id").getValue());
        assertEquals("worker-1", clauses.getString("lease.owner").getValue(),
                "must match the owner, or a stale worker's write lands");
        assertTrue(clauses.getDocument("lease.expiresAt").containsKey("$gt"),
                "must require a live lease, or an expired owner's write lands");
    }

    /** The B1 fix: a terminal failure leaves the queue; a retryable one stays in it. */
    @Test
    void terminalFailureClearsTheReindexFlagButRetryableDoesNot() {
        BsonDocument terminal = render(repo.failUpdate(EntityStatus.FAILED, "boom", 6, null))
                .getDocument("$set");
        assertFalse(terminal.getBoolean("needsReindex").getValue(),
                "a dead-letter must drop out of the indexing queue");

        BsonDocument retryable = render(
                repo.failUpdate(EntityStatus.INGESTED, "boom", 1, Instant.now())).getDocument("$set");
        assertFalse(retryable.containsKey("needsReindex"),
                "a retryable failure leaves the flag alone; INGESTED already re-queues it");
    }

    /** The B5 fix: success zeroes the streak, so retryLimit means "n in a row". */
    @Test
    void successResetsTheRetryStreak() {
        BsonDocument set = render(repo.indexedUpdate(3, "model", Instant.now())).getDocument("$set");

        assertEquals("INDEXED", set.getString("status").getValue());
        assertEquals(0, set.getDocument("retry").getInt32("count").getValue(),
                "retry.count is consecutive failures, not lifetime ones");
        assertTrue(set.getDocument("retry").isNull("nextAttemptAt"));
    }
}
