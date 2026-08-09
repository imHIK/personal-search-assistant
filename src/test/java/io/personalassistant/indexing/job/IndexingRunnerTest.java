package io.personalassistant.indexing.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A runner over the shared fakes, so a test can swap in a deliberately broken embedding provider. */
    private IndexingRunner runnerWith(FakeEmbeddingProvider embeddings) {
        IndexingRunner r = new IndexingRunner(entities, knowledge, new PlainTextParserRegistry(),
                new SingleChunkingRegistry(new WholeTextChunkingStrategy()), new ChunkingSpecResolver(),
                embeddings, index);
        r.embedBatch = 64;
        r.retryLimit = 2;
        r.backoffSeconds = 30;
        r.leaseSeconds = 120;
        return r;
    }

    /**
     * Claim through the real work-queue path rather than handing the runner a lease-less entity —
     * every terminal write is now lease-fenced, so a test that skips the claim is not testing the
     * production path.
     */
    private Entity claim(String id) {
        return entities.claimForIndexing(10, WORKER, LEASE).stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("entity " + id + " was not claimable"));
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
    void recordsRetryableFailureWhenFileMissing() {
        entities.upsert(TestData.ingestedFile("ent_3", "kn_1", "missing", "/no/such/file.txt", "text/plain"));

        runner.indexEntity(claim("ent_3"), WORKER);

        Entity stored = entities.findById("ent_3").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "retryable failure returns to the queue");
        assertEquals(1, stored.retry().count());
        assertNotNull(stored.index().error());
        assertNotNull(stored.retry().nextAttemptAt(), "backoff time should be set");
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

    /**
     * B2 regression. A worker whose lease lapsed mid-run must not record its outcome — the entity was
     * re-claimed by someone else who is now working on it, and a late markIndexed would both report a
     * half-finished run as complete and unset the new owner's lease.
     */
    @Test
    void staleWorkerCannotRecordItsOutcome() {
        entities.upsert(TestData.ingestedText("ent_5", "kn_1", "doc5", "hello"));
        Entity claimed = claim("ent_5");

        // Same entity, but run under an owner that does not hold the lease.
        runner.indexEntity(claimed, "some-other-worker");

        Entity stored = entities.findById("ent_5").orElseThrow();
        assertEquals(EntityStatus.INDEXING, stored.status(), "the real owner still owns the entity");
        assertEquals(0, stored.index().chunkCount(), "a fenced-out run records nothing");
        assertNotNull(stored.lease(), "the fenced write must not clear the live owner's lease");
        assertEquals(WORKER, stored.lease().owner());
    }

    /**
     * B5 regression: retry.count is <em>consecutive</em> failures. Without the reset it accumulates
     * for the entity's whole lifetime, so something that fails once a month is dead-lettered after
     * retryLimit months of otherwise-successful indexing.
     */
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

    /**
     * B7 regression. A chunk whose vector is missing is still accepted by OpenSearch (the mapping
     * does not require the field), counted by markIndexed, and then invisible to semantic search
     * forever. It must be a loud failure, not a silent success.
     */
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

    /** Same contract, the other way a provider can break it: fewer vectors than chunks. */
    @Test
    void aShortEmbeddingResponseFailsTheRun() {
        runner = runnerWith(new FakeEmbeddingProvider(8).breaking(FakeEmbeddingProvider.Defect.SHORT));
        entities.upsert(TestData.ingestedText("ent_9", "kn_1", "doc9", "some text"));

        runner.indexEntity(claim("ent_9"), WORKER);

        assertTrue(index.indexed.isEmpty());
        assertEquals(EntityStatus.INGESTED, entities.findById("ent_9").orElseThrow().status());
    }

    /**
     * B8a regression. A bulk that OpenSearch partly rejects must not be recorded as a success — the
     * entity would claim a chunk count it does not have and never be retried.
     */
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

    /**
     * B1 regression. A dead-lettered entity must leave the work queue entirely; before the fix it was
     * re-claimed on every tick forever, consuming the per-knowledge quota and the global batch budget.
     */
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

        // The sanctioned way back in, with a fresh budget.
        entities.flagNeedsReindex("ent_7");
        Entity revived = entities.findById("ent_7").orElseThrow();
        assertEquals(EntityStatus.INGESTED, revived.status());
        assertEquals(0, revived.retry().count(), "a revived entity gets a fresh retry budget");
        assertNull(revived.index().error());
        assertEquals(1, entities.claimForIndexing(10, "any-worker", LEASE).size());
    }
}
