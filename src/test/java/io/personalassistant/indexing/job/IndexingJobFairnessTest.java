package io.personalassistant.indexing.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.indexing.chunking.ChunkingSpecResolver;
import io.personalassistant.testsupport.AlwaysGrantPermitService;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.PlainTextParserRegistry;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.SingleChunkingRegistry;
import io.personalassistant.testsupport.TestData;
import io.personalassistant.testsupport.WholeTextChunkingStrategy;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the indexing job spreads work across knowledges instead of draining one backlog first.
 * One knowledge has a large backlog and two have small ones; a single tick must touch all three.
 */
class IndexingJobFairnessTest {

    private InMemoryEntityRepository entities;
    private InMemoryKnowledgeRepository knowledge;
    private RecordingSearchIndex index;
    private IndexingJob job;

    @BeforeEach
    void setUp() {
        entities = new InMemoryEntityRepository();
        knowledge = new InMemoryKnowledgeRepository();
        index = new RecordingSearchIndex();

        for (String kid : new String[] {"kn_big", "kn_b", "kn_c"}) {
            knowledge.save(TestData.knowledge(kid, SourceType.LOCAL_FS, Instant.now(), java.util.Map.of()));
        }
        // Large backlog on kn_big, small on the others.
        seed("kn_big", 20);
        seed("kn_b", 2);
        seed("kn_c", 2);

        IndexingRunner runner = new IndexingRunner(entities, knowledge, new PlainTextParserRegistry(),
                new SingleChunkingRegistry(new WholeTextChunkingStrategy()), new ChunkingSpecResolver(),
                new FakeEmbeddingProvider(8), index);
        runner.embedBatch = 64;
        runner.retryLimit = 2;
        runner.backoffSeconds = 30;
        runner.leaseSeconds = 120;

        AlwaysGrantPermitService permits = new AlwaysGrantPermitService();

        job = new IndexingJob(entities, permits, runner);
        job.batch = 6;          // global budget per tick
        job.perKnowledge = 2;   // quota per knowledge per tick
        job.maxKnowledges = 200;
        job.concurrency = 4;
        job.permitTtlSeconds = 300;
    }

    private void seed(String knowledgeId, int count) {
        for (int i = 0; i < count; i++) {
            entities.upsert(TestData.ingestedText(knowledgeId + "_ent_" + i, knowledgeId,
                    "doc" + i, "content " + i));
        }
    }

    @Test
    void oneTickIndexesAcrossAllKnowledges() {
        job.tick();

        Set<String> indexedKnowledges = index.indexed.stream()
                .map(Chunk::knowledgeId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("kn_big", "kn_b", "kn_c"), indexedKnowledges,
                "a single tick must make progress on every knowledge, not just the big backlog");
        // budget 6 = 2 per knowledge × 3 knowledges
        assertEquals(6, index.indexed.size());
        assertEquals(2, entities.countByKnowledgeAndStatus("kn_big", EntityStatus.INDEXED),
                "the big backlog is drained only a quota at a time");
        assertTrue(entities.countByKnowledgeAndStatus("kn_big", EntityStatus.INGESTED) > 0,
                "the rest of the big backlog waits its turn");
    }
}
