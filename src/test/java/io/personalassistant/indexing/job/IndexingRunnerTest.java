package io.personalassistant.indexing.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.indexing.chunking.ChunkingSpecResolver;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.PlainTextParserRegistry;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.SingleChunkingRegistry;
import io.personalassistant.testsupport.TestData;
import io.personalassistant.testsupport.WholeTextChunkingStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingRunnerTest {

    private static final String WORKER = "idx-worker-1";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private InMemoryEntityRepository entities;
    private InMemoryKnowledgeRepository knowledge;
    private RecordingSearchIndex index;
    private IndexingRunner runner;

    @BeforeEach
    void setUp() {
        entities = new InMemoryEntityRepository();
        knowledge = new InMemoryKnowledgeRepository();
        index = new RecordingSearchIndex();
        runner = runnerWith(new FakeEmbeddingProvider(8));

        knowledge.save(TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), java.util.Map.of()));
    }

    private IndexingRunner runnerWith(FakeEmbeddingProvider embeddings) {
        IndexingRunner r = new IndexingRunner(entities, knowledge, new PlainTextParserRegistry(),
                new SingleChunkingRegistry(new WholeTextChunkingStrategy()), new ChunkingSpecResolver(),
                embeddings, index, FieldSets.bundled());
        r.embedBatch = 64;
        r.retryLimit = 2;
        r.backoffSeconds = 30;
        r.leaseSeconds = 120;
        r.maxDeferrals = 4;
        return r;
    }

    /**
     * Claims through the work queue: terminal writes are lease-fenced, so a lease-less entity would not
     * exercise the production path.
     */
    private Entity claim(String id) {
        return entities.claimForIndexing(10, WORKER, LEASE).stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("entity " + id + " was not claimable"));
    }

    @Test
    void aRateLimitedEntityIsDeferredToTheReopeningInstantRatherThanFailed() {
        Instant reopensAt = Instant.now().plusSeconds(3600);
        FakeEmbeddingProvider throttled = new FakeEmbeddingProvider(8);
        throttled.rateLimitedUntil = reopensAt;
        IndexingRunner limited = runnerWith(throttled);
        entities.upsert(TestData.ingestedText("ent_rl", "kn_1", "doc", "hello"));

        limited.indexEntity(claim("ent_rl"), WORKER);

        Entity stored = entities.findById("ent_rl").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "a deferral is retryable, not terminal");
        assertEquals(reopensAt, stored.retry().nextAttemptAt(),
                "the retry lands when the window reopens, not after the flat backoff");
        assertTrue(stored.index().error().contains("Rate limited"), stored.index().error());
    }

    @Test
    void deferralsDoNotCountAgainstTheOrdinaryRetryLimit() {
        FakeEmbeddingProvider throttled = new FakeEmbeddingProvider(8);
        // Reopens at once so every pass can re-claim it; the deferral count is under test, not the backoff.
        throttled.rateLimitedUntil = Instant.now();
        IndexingRunner limited = runnerWith(throttled); // retryLimit = 2, maxDeferrals = 4
        entities.upsert(TestData.ingestedText("ent_rl2", "kn_1", "doc", "hello"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            limited.indexEntity(claim("ent_rl2"), WORKER);
            assertEquals(EntityStatus.INGESTED, entities.findById("ent_rl2").orElseThrow().status(),
                    "still retryable after " + attempt + " deferrals (retryLimit is 2)");
        }
    }

    @Test
    void aPersistentlyRateLimitedEntityEventuallyDeadLetters() {
        FakeEmbeddingProvider throttled = new FakeEmbeddingProvider(8);
        throttled.rateLimitedUntil = Instant.now();
        IndexingRunner limited = runnerWith(throttled); // maxDeferrals = 4
        entities.upsert(TestData.ingestedText("ent_rl3", "kn_1", "doc", "hello"));

        for (int attempt = 1; attempt <= 5; attempt++) {
            limited.indexEntity(claim("ent_rl3"), WORKER);
        }

        Entity stored = entities.findById("ent_rl3").orElseThrow();
        assertEquals(EntityStatus.FAILED, stored.status());
        assertNull(stored.retry().nextAttemptAt(), "a dead-lettered entity is not auto-reclaimed");
    }

    @Test
    void indexesTextEntityAndRecordsResult() {
        entities.upsert(TestData.ingestedText("ent_1", "kn_1", "doc1", "hello world"));

        runner.indexEntity(claim("ent_1"), WORKER);

        assertEquals(1, index.indexed.size());
        assertEquals("hello world", index.indexed.get(0).text());
        assertNotNull(index.indexed.get(0).embedding(), "chunk must be embedded before indexing");
        assertTrue(index.deletedEntities.contains("ent_1"), "old chunks replaced before writing new ones");

        Entity stored = entities.findById("ent_1").orElseThrow();
        assertEquals(EntityStatus.INDEXED, stored.status());
        assertEquals(1, stored.index().chunkCount());
        assertEquals("fake-8", stored.index().embeddingModel());
        assertNull(stored.lease(), "a completed run releases the lease");
    }

    @Test
    void extractsTextFromFileEntity(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("note.txt");
        Files.writeString(file, "contents from disk");
        entities.upsert(TestData.ingestedFile("ent_2", "kn_1", "note", file.toString(), "text/plain"));

        runner.indexEntity(claim("ent_2"), WORKER);

        assertEquals(1, index.indexed.size());
        assertEquals("contents from disk", index.indexed.get(0).text());
        assertEquals(EntityStatus.INDEXED, entities.findById("ent_2").orElseThrow().status());
    }

    @Test
    void deadLettersImmediatelyAndFlagsForRefetchWhenTheFileIsGone() {
        entities.upsert(TestData.ingestedFile("ent_3", "kn_1", "missing", "/no/such/file.txt", "text/plain"));

        runner.indexEntity(claim("ent_3"), WORKER);

        Entity stored = entities.findById("ent_3").orElseThrow();
        assertEquals(EntityStatus.FAILED, stored.status(), "no retry can make the file reappear");
        assertEquals(0, stored.retry().count(), "no retry budget is consumed either");
        assertNull(stored.retry().nextAttemptAt(), "and nothing is scheduled to re-attempt it");
        assertFalse(stored.needsReindex(), "it has left the indexing queue");
        assertTrue(stored.needsRefetch(), "but the next walk must re-materialize it");
        assertTrue(stored.index().error().contains("/no/such/file.txt"),
                "the error names the path that vanished: " + stored.index().error());
    }

    @Test
    void stillRetriesWhenTheFileExistsButCannotBeRead(@TempDir Path dir) throws IOException {
        Path unreadable = dir.resolve("locked");
        Files.createDirectory(unreadable); // a directory opens, then fails on read — present, unusable
        entities.upsert(TestData.ingestedFile("ent_3b", "kn_1", "locked", unreadable.toString(), "text/plain"));

        runner.indexEntity(claim("ent_3b"), WORKER);

        Entity stored = entities.findById("ent_3b").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "retryable failure returns to the queue");
        assertEquals(1, stored.retry().count());
        assertNotNull(stored.retry().nextAttemptAt(), "backoff time should be set");
        assertFalse(stored.needsRefetch(), "a present-but-unreadable file is not a missing one");
    }

    @Test
    void deletesChunksForTombstonedEntity() {
        entities.upsert(TestData.ingestedText("ent_4", "kn_1", "doc4", "bye"));
        entities.markDeleted("ent_4", Instant.now());
        Entity claimed = entities.claimForDeletion(10, WORKER, LEASE).get(0);

        runner.deleteEntityChunks(claimed, WORKER);

        assertTrue(index.deletedEntities.contains("ent_4"));
        Entity stored = entities.findById("ent_4").orElseThrow();
        assertEquals(EntityStatus.DELETED, stored.status());
        assertEquals(false, stored.needsReindex(), "cleanup clears the deletion flag");
    }

    @Test
    void staleWorkerCannotRecordItsOutcome() {
        entities.upsert(TestData.ingestedText("ent_5", "kn_1", "doc5", "hello"));
        Entity claimed = claim("ent_5");

        runner.indexEntity(claimed, "some-other-worker");

        Entity stored = entities.findById("ent_5").orElseThrow();
        assertEquals(EntityStatus.INDEXING, stored.status(), "the real owner still owns the entity");
        assertEquals(0, stored.index().chunkCount(), "a fenced-out run records nothing");
        assertNotNull(stored.lease(), "the fenced write must not clear the live owner's lease");
        assertEquals(WORKER, stored.lease().owner());
    }

    @Test
    void successResetsTheConsecutiveFailureStreak() {
        entities.upsert(TestData.ingestedText("ent_6", "kn_1", "doc6", "fine now"));
        entities.seedFailed("ent_6", EntityStatus.INGESTED, "an earlier hiccup", 2);

        runner.indexEntity(claim("ent_6"), WORKER);

        Entity stored = entities.findById("ent_6").orElseThrow();
        assertEquals(EntityStatus.INDEXED, stored.status());
        assertEquals(0, stored.retry().count(), "a success ends the streak");
        assertNull(stored.index().error(), "a success clears the recorded error");
    }

    @Test
    void anEmbeddingHoleFailsTheRunInsteadOfIndexingAVectorlessChunk() {
        runner = runnerWith(new FakeEmbeddingProvider(8).breaking(FakeEmbeddingProvider.Defect.HOLE));
        entities.upsert(TestData.ingestedText("ent_8", "kn_1", "doc8", "some text"));

        runner.indexEntity(claim("ent_8"), WORKER);

        assertTrue(index.indexed.isEmpty(), "nothing may reach the index when a vector is missing");
        Entity stored = entities.findById("ent_8").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "it took the retry path");
        assertEquals(1, stored.retry().count());
        assertEquals(0, stored.index().chunkCount(), "and recorded no chunk count");
        assertNotNull(stored.index().error());
    }

    @Test
    void aShortEmbeddingResponseFailsTheRun() {
        runner = runnerWith(new FakeEmbeddingProvider(8).breaking(FakeEmbeddingProvider.Defect.SHORT));
        entities.upsert(TestData.ingestedText("ent_9", "kn_1", "doc9", "some text"));

        runner.indexEntity(claim("ent_9"), WORKER);

        assertTrue(index.indexed.isEmpty());
        assertEquals(EntityStatus.INGESTED, entities.findById("ent_9").orElseThrow().status());
    }

    @Test
    void aRejectedBulkIsNotRecordedAsSuccess() {
        entities.upsert(TestData.ingestedText("ent_10", "kn_1", "doc10", "some text"));
        index.indexChunksFailure = new IllegalStateException("1 of 1 chunks rejected by OpenSearch");

        runner.indexEntity(claim("ent_10"), WORKER);

        Entity stored = entities.findById("ent_10").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "it took the retry path");
        assertEquals(0, stored.index().chunkCount(), "no chunk count is claimed for a rejected write");
        assertNotNull(stored.index().error());
        assertTrue(stored.index().error().contains("rejected"), "the reason survives onto the entity");
    }

    @Test
    void terminalFailureLeavesTheIndexingQueueUntilExplicitlyRevived() {
        entities.upsert(TestData.ingestedFile("ent_7", "kn_1", "gone", "/no/such/file.txt", "text/plain"));
        // Start one failure short of the limit so the next failure is terminal.
        entities.seedFailed("ent_7", EntityStatus.INGESTED, "earlier", runner.retryLimit);

        runner.indexEntity(claim("ent_7"), WORKER);

        Entity dead = entities.findById("ent_7").orElseThrow();
        assertEquals(EntityStatus.FAILED, dead.status());
        assertFalse(dead.needsReindex(), "a dead-letter must not stay flagged for reindexing");
        assertTrue(entities.claimForIndexing(10, "any-worker", LEASE).isEmpty(),
                "a dead-lettered entity is never re-claimed");
        assertTrue(entities.distinctPendingKnowledgeIds(10).isEmpty(),
                "and it must not keep its knowledge in the pending rotation either");

        entities.flagNeedsReindex("ent_7");
        Entity revived = entities.findById("ent_7").orElseThrow();
        assertEquals(EntityStatus.INGESTED, revived.status());
        assertEquals(0, revived.retry().count(), "a revived entity gets a fresh retry budget");
        assertNull(revived.index().error());
        assertEquals(1, entities.claimForIndexing(10, "any-worker", LEASE).size());
    }
}
