package io.personalassistant.ingestion.job;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A knowledge whose credentials are known-bad must not be run: an expired token otherwise fails on
 * every tick, burning a lease and a permit each time, forever.
 *
 * <p>The permit service and runner are {@code null} deliberately — reaching either means the job did
 * <em>not</em> skip, so an NPE is the assertion.
 */
class IngestionJobConnectionHealthTest {

    private static final SourceType TYPE = SourceType.SLACK;

    private final InMemoryKnowledgeRepository knowledge = new InMemoryKnowledgeRepository();
    private final InMemoryCursorRepository cursors = new InMemoryCursorRepository();

    private IngestionJob job(ConnectionStatus status, boolean requiresConnection) {
        StubConnector connector = new StubConnector(TYPE, List.of(new SourceIterable("root", "root", Map.of())))
                .withRequiresConnection(requiresConnection);
        ConnectionResolver resolver = kn -> new Connection("conn_1", "Work", TYPE.name(), Map.of(), Map.of(),
                null, true, status, null, Instant.now(), Instant.now());
        IngestionJob job = new IngestionJob(cursors, knowledge, null, null,
                new SingleConnectorRegistry(connector), resolver);
        job.pollBatch = 20;
        return job;
    }

    private String activeKnowledgeWithClaimableCursor(String id) {
        knowledge.save(TestData.knowledge(id, TYPE, Instant.now(), Map.of()));
        Cursor cursor = TestData.cursor(id, "root", CursorDirection.FORWARD, TYPE);
        cursors.insertIfAbsent(cursor);
        return cursor.id();
    }

    @Test
    void skipsAKnowledgeWhoseConnectionIsInError() {
        String cursorId = activeKnowledgeWithClaimableCursor("kn_1");

        Assertions.assertDoesNotThrow(() -> job(ConnectionStatus.ERROR, true).tick());
        Assertions.assertEquals(CursorStatus.AVAILABLE,
                cursors.findById(cursorId).orElseThrow().status(),
                "the cursor must be left claimable — nothing is paused, so recovery is automatic");
    }

    @Test
    void runsAKnowledgeWhoseConnectionIsHealthy() {
        // Reaching the runner NPEs on the null collaborators, which is how we know it was not skipped.
        activeKnowledgeWithClaimableCursor("kn_1");

        Assertions.assertThrows(Exception.class, () -> job(ConnectionStatus.ACTIVE, true).tick());
    }

    @Test
    void aConnectorNeedingNoCredentialsIsNeverSkipped() {
        activeKnowledgeWithClaimableCursor("kn_1");

        Assertions.assertThrows(Exception.class, () -> job(ConnectionStatus.ERROR, false).tick());
    }

    @Test
    void anUnresolvableConnectionRunsAsBeforeRatherThanBlockingSync() {
        // The skip is an optimisation; doubt must never be the reason a sync stops.
        activeKnowledgeWithClaimableCursor("kn_1");
        StubConnector connector = new StubConnector(TYPE, List.of()).withRequiresConnection(true);
        IngestionJob job = new IngestionJob(cursors, knowledge, null, null,
                new SingleConnectorRegistry(connector),
                kn -> {
                    throw new NoSuchElementException("no default connection");
                });
        job.pollBatch = 20;

        Assertions.assertThrows(Exception.class, job::tick);
    }
}
