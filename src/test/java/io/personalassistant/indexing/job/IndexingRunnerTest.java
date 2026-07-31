package io.personalassistant.indexing.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingRunnerTest {

    private InMemoryEntityRepository entities;
    private InMemoryKnowledgeRepository knowledge;
    private RecordingSearchIndex index;
    private IndexingRunner runner;

    @BeforeEach
    void setUp() {
        entities = new InMemoryEntityRepository();
        knowledge = new InMemoryKnowledgeRepository();
        index = new RecordingSearchIndex();
        runner = new IndexingRunner(entities, knowledge, new PlainTextParserRegistry(),
                new SingleChunkingRegistry(new WholeTextChunkingStrategy()), new ChunkingSpecResolver(),
                new FakeEmbeddingProvider(8), index);
        runner.embedBatch = 64;
        runner.retryLimit = 2;
        runner.backoffSeconds = 30;
        runner.leaseSeconds = 120;

        knowledge.save(TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), java.util.Map.of()));
    }

    @Test
    void indexesTextEntityAndRecordsResult() {
        Entity entity = entities.upsert(TestData.ingestedText("ent_1", "kn_1", "doc1", "hello world"));

        runner.indexEntity(entity);

        assertEquals(1, index.indexed.size());
        assertEquals("hello world", index.indexed.get(0).text());
        assertNotNull(index.indexed.get(0).embedding(), "chunk must be embedded before indexing");
        assertTrue(index.deletedEntities.contains("ent_1"), "old chunks replaced before writing new ones");

        Entity stored = entities.findById("ent_1").orElseThrow();
        assertEquals(EntityStatus.INDEXED, stored.status());
        assertEquals(1, stored.index().chunkCount());
        assertEquals("fake-8", stored.index().embeddingModel());
    }

    @Test
    void extractsTextFromFileEntity(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("note.txt");
        Files.writeString(file, "contents from disk");
        Entity entity = entities.upsert(
                TestData.ingestedFile("ent_2", "kn_1", "note", file.toString(), "text/plain"));

        runner.indexEntity(entity);

        assertEquals(1, index.indexed.size());
        assertEquals("contents from disk", index.indexed.get(0).text());
        assertEquals(EntityStatus.INDEXED, entities.findById("ent_2").orElseThrow().status());
    }

    @Test
    void recordsRetryableFailureWhenFileMissing() {
        Entity entity = entities.upsert(
                TestData.ingestedFile("ent_3", "kn_1", "missing", "/no/such/file.txt", "text/plain"));

        runner.indexEntity(entity);

        Entity stored = entities.findById("ent_3").orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "retryable failure returns to the queue");
        assertEquals(1, stored.retry().count());
        assertNotNull(stored.index().error());
        assertNotNull(stored.retry().nextAttemptAt(), "backoff time should be set");
    }

    @Test
    void deletesChunksForTombstonedEntity() {
        Entity entity = entities.upsert(TestData.ingestedText("ent_4", "kn_1", "doc4", "bye"));
        entities.markDeleted("ent_4", Instant.now());

        runner.deleteEntityChunks(entities.findById("ent_4").orElseThrow());

        assertTrue(index.deletedEntities.contains("ent_4"));
        Entity stored = entities.findById("ent_4").orElseThrow();
        assertEquals(EntityStatus.DELETED, stored.status());
        assertEquals(false, stored.needsReindex(), "cleanup clears the deletion flag");
    }
}
