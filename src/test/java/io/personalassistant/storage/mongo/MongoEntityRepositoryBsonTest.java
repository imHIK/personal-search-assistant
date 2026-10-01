package io.personalassistant.storage.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoClientSettings;
import io.personalassistant.domain.model.EnrichmentOutcome;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

class MongoEntityRepositoryBsonTest {

    private final MongoEntityRepository repo = new MongoEntityRepository(null, "test_db");

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    private static Entity anEntity() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Entity("ent_1", "kn_1", "root", EntityType.FILE, "ext_1", Map.of(),
                Entity.Content.ofText("body"), Map.of("title", "t"), "sha256:abc",
                EntityStatus.INGESTED, false, false, Entity.IndexInfo.empty(), null, Entity.Retry.zero(),
                now, now, null, 7L);
    }

    @Test
    void upsertIsFieldLevelAndDropsTheLease() {
        BsonDocument update = render(repo.upsertUpdate(anEntity()));

        BsonDocument set = update.getDocument("$set");
        assertTrue(set.containsKey("checksum"), "content fields are written");
        assertEquals("INGESTED", set.getString("status").getValue(), "the work queue is reset");
        assertFalse(set.getBoolean("needsReindex").getValue());
        assertFalse(set.getBoolean("needsRefetch").getValue(),
                "the content just written is the fresh copy, so the re-fetch flag is spent here");
        assertEquals(0, set.getDocument("retry").getInt32("count").getValue());

        assertTrue(update.getDocument("$unset").containsKey("lease"),
                "dropping the lease is what fences out an indexer on the previous revision");

        assertFalse(set.containsKey("index"), "must not overwrite the whole index sub-document");
        assertFalse(set.containsKey("index.chunkCount"));
        assertFalse(set.containsKey("index.embeddingModel"));
        assertTrue(set.containsKey("index.error"), "only the stale error is cleared");

        BsonDocument onInsert = update.getDocument("$setOnInsert");
        assertTrue(onInsert.containsKey("_id") && onInsert.containsKey("createdAt"),
                "identity is set on insert only, so a replay preserves it");
    }

    @Test
    void missingContentDeadLettersAtOnceAndAsksForARefetch() {
        BsonDocument update = render(repo.contentMissingUpdate("Staged content missing at /tmp/x"));
        BsonDocument set = update.getDocument("$set");

        assertEquals("FAILED", set.getString("status").getValue());
        assertFalse(set.getBoolean("needsReindex").getValue(), "it leaves the indexing queue");
        assertTrue(set.getBoolean("needsRefetch").getValue(), "and joins the ingestion one");
        assertEquals(0, set.getDocument("retry").getInt32("count").getValue(),
                "no retry budget is spent on a file that cannot come back");
        assertTrue(set.getDocument("retry").isNull("nextAttemptAt"),
                "and nothing is scheduled, or the console shows it as merely pending");
        assertTrue(update.getDocument("$unset").containsKey("lease"));
    }

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

    @Test
    void upsertNeverTouchesEnrichedValuesOrUserMarks() {
        BsonDocument update = render(repo.upsertUpdate(anEntity()));

        for (String op : List.of("$set", "$unset", "$setOnInsert")) {
            BsonDocument fields = update.containsKey(op) ? update.getDocument(op) : new BsonDocument();
            assertFalse(fields.keySet().stream().anyMatch(k -> k.startsWith("enrich") || k.startsWith("custom")),
                    op + " must leave the indexer's and the user's fields alone: " + fields.keySet());
        }
    }

    @Test
    void enrichmentRidesOnTheFencedIndexedWrite() {
        Entity.Enrichment stamp = new Entity.Enrichment("task_1", Instant.now(), "c1", Instant.now(), null);
        BsonDocument set = render(repo.indexedUpdate(1, "m", Instant.now(),
                EnrichmentOutcome.set(Map.of("yoe", 5L), stamp))).getDocument("$set");
        assertEquals(5L, set.getDocument("enriched").getInt64("yoe").getValue());
        assertEquals("task_1", set.getDocument("enrichment").getString("taskId").getValue());

        BsonDocument error = render(repo.indexedUpdate(1, "m", Instant.now(), EnrichmentOutcome.error(stamp)));
        assertFalse(error.getDocument("$set").containsKey("enriched"), "an error keeps the previous values");

        BsonDocument clear = render(repo.indexedUpdate(1, "m", Instant.now(), EnrichmentOutcome.clear()));
        assertTrue(clear.getDocument("$unset").containsKey("enriched"));
        assertTrue(clear.getDocument("$unset").containsKey("enrichment"));
    }

    @Test
    void successResetsTheRetryStreak() {
        BsonDocument set = render(repo.indexedUpdate(3, "model", Instant.now())).getDocument("$set");

        assertEquals("INDEXED", set.getString("status").getValue());
        assertEquals(0, set.getDocument("retry").getInt32("count").getValue(),
                "retry.count is consecutive failures, not lifetime ones");
        assertTrue(set.getDocument("retry").isNull("nextAttemptAt"));
    }
}
