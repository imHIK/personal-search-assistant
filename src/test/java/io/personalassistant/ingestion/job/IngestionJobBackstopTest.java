package io.personalassistant.ingestion.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ingestion loop's backstop: a cursor that is claimable while its knowledge is paused (e.g. it
 * was IN_PROGRESS at pause time and rested AVAILABLE when its lease ended) must be parked by the
 * job so it stops re-polluting the bounded claim batch. The permit service and runner are never
 * reached on the inactive path, so {@code null} is passed for both deliberately.
 */
class IngestionJobBackstopTest {

    private InMemoryKnowledgeRepository knowledge;
    private InMemoryCursorRepository cursors;
    private IngestionJob job;

    @BeforeEach
    void setUp() {
        knowledge = new InMemoryKnowledgeRepository();
        cursors = new InMemoryCursorRepository();
        // Connector registry and resolver are only consulted on the ACTIVE path, which these
        // tests never reach; nulls would NPE there, so a registry supporting nothing is passed.
        job = new IngestionJob(cursors, knowledge, null, null,
                new SingleConnectorRegistry(new StubConnector(SourceType.SLACK, java.util.List.of())),
                kn -> {
                    throw new java.util.NoSuchElementException("no connection");
                });
        job.pollBatch = 20;
    }

    private Knowledge pausedKnowledge(String id) {
        knowledge.save(TestData.knowledge(id, SourceType.SLACK, Instant.now(), java.util.Map.of()));
        knowledge.updateStatus(id, KnowledgeStatus.PAUSED);
        return knowledge.findById(id).orElseThrow();
    }

    @Test
    void parksClaimableCursorsOfPausedKnowledge() {
        pausedKnowledge("k1");
        cursors.insertIfAbsent(TestData.cursor("k1", "chan", CursorDirection.FORWARD, SourceType.SLACK));
        assertEquals(1, cursors.findClaimable(20).size(), "precondition: the straggler is claimable");

        job.tick();

        assertTrue(cursors.findByKnowledge("k1").stream()
                        .allMatch(c -> c.status() == CursorStatus.SUSPENDED),
                "the backstop parks the paused knowledge's claimable cursors");
        assertTrue(cursors.findClaimable(20).isEmpty(), "batch is no longer polluted next tick");
    }

    @Test
    void leavesOrphanCursorAlone() {
        // No knowledge row for "ghost" — deletion path owns cleanup; the job must not park it.
        cursors.insertIfAbsent(TestData.cursor("ghost", "chan", CursorDirection.FORWARD, SourceType.SLACK));

        job.tick();

        assertEquals(CursorStatus.AVAILABLE,
                cursors.findById("cur_ghostchan" + CursorDirection.FORWARD).orElseThrow().status(),
                "an orphan cursor is left untouched, not parked");
    }
}
