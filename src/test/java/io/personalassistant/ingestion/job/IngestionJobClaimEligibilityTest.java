package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The claim batch is bounded and ordered least-recently-run first, and a cursor the job skips is never
 * written — so a skipped cursor keeps its place at the head of the batch on every tick. Twenty of them
 * stop every source; 71 orphan cursors left by a delete that raced activation did exactly that for a
 * week. These tests seed more blockers than the batch holds, ahead of one runnable cursor.
 *
 * <p>The permit service and runner are {@code null} deliberately: reaching either NPEs, which is how a
 * test knows a cursor got past every skip check — the same device {@code IngestionJobConnectionHealthTest}
 * uses.
 */
class IngestionJobClaimEligibilityTest {

    private static final SourceType TYPE = SourceType.SLACK;

    private final InMemoryKnowledgeRepository knowledge = new InMemoryKnowledgeRepository();
    private final InMemoryCursorRepository cursors = new InMemoryCursorRepository();

    private IngestionJob job(boolean requiresConnection, Set<String> brokenConnections) {
        StubConnector connector = new StubConnector(TYPE, List.of()).withRequiresConnection(requiresConnection);
        ConnectionResolver resolver = kn -> new Connection("conn_" + kn.id(), "Work", TYPE.name(), Map.of(),
                Map.of(), null, true,
                brokenConnections.contains(kn.id()) ? ConnectionStatus.ERROR : ConnectionStatus.ACTIVE,
                null, Instant.now(), Instant.now());
        IngestionJob job = new IngestionJob(cursors, knowledge, null, null,
                new SingleConnectorRegistry(connector), resolver);
        job.pollBatch = 20;
        return job;
    }

    private void knowledgeIn(String id, KnowledgeStatus status) {
        knowledge.save(TestData.knowledge(id, TYPE, Instant.now(), Map.of()));
        knowledge.updateStatus(id, status);
    }

    private void neverRunCursors(String knowledgeId, int count) {
        for (int i = 0; i < count; i++) {
            cursors.insertIfAbsent(TestData.cursor(knowledgeId, "it" + i, CursorDirection.FORWARD, TYPE));
        }
    }

    private boolean allAvailable(String knowledgeId) {
        return cursors.findByKnowledge(knowledgeId).stream().allMatch(c -> c.status() == CursorStatus.AVAILABLE);
    }

    @Test
    void orphanAndErroredKnowledgeCursorsDoNotStarveAnActiveOne() {
        neverRunCursors("ghost", 25);                 // no knowledge row at all
        knowledgeIn("errored", KnowledgeStatus.ERROR);
        neverRunCursors("errored", 25);
        knowledgeIn("live", KnowledgeStatus.ACTIVE);
        neverRunCursors("live", 1);
        Assertions.assertTrue(cursors.findClaimable(List.of("ghost", "errored", "live"), 20).stream()
                        .map(Cursor::knowledgeId).noneMatch("live"::equals),
                "precondition: unfiltered, the blockers fill the whole batch");

        Assertions.assertThrows(NullPointerException.class, () -> job(false, Set.of()).tick(),
                "the active knowledge's cursor reached the permit step");
        Assertions.assertTrue(allAvailable("ghost") && allAvailable("errored"), "blockers are not written to");
    }

    @Test
    void aKnowledgeWithAnExpiredConnectionDoesNotStarveAHealthyOne() {
        knowledgeIn("expired", KnowledgeStatus.ACTIVE);
        neverRunCursors("expired", 25);
        knowledgeIn("healthy", KnowledgeStatus.ACTIVE);
        neverRunCursors("healthy", 1);

        Assertions.assertThrows(NullPointerException.class, () -> job(true, Set.of("expired")).tick(),
                "the healthy knowledge's cursor reached the permit step");
        Assertions.assertTrue(allAvailable("expired"),
                "nothing is parked, so the source resumes by itself once its connection recovers");
    }

    @Test
    void aTickWithNothingEligibleDoesNothing() {
        neverRunCursors("ghost", 25);
        knowledgeIn("draft", KnowledgeStatus.DRAFT);
        neverRunCursors("draft", 25);

        Assertions.assertDoesNotThrow(() -> job(false, Set.of()).tick());
        Assertions.assertTrue(allAvailable("ghost") && allAvailable("draft"));
    }
}
