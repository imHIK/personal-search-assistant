package io.personalassistant.ingestion.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestionRunnerTest {

    private InMemoryEntityRepository entities;
    private InMemoryCursorRepository cursors;
    private InMemoryKnowledgeRepository knowledge;
    private StubConnector connector;
    private IngestionRunner runner;
    private Knowledge kn;

    @BeforeEach
    void setUp() {
        entities = new InMemoryEntityRepository();
        cursors = new InMemoryCursorRepository();
        knowledge = new InMemoryKnowledgeRepository();
        connector = new StubConnector(SourceType.LOCAL_FS,
                List.of(new SourceIterable("root", "root", Map.of())));
        runner = new IngestionRunner(new SingleConnectorRegistry(connector), entities, cursors, knowledge);
        runner.batchesPerLease = 50;
        runner.maxItemsPerBatch = 100;
        runner.leaseSeconds = 60;
        runner.retryLimit = 2;

        kn = TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), Map.of("rootPath", "/tmp"));
        knowledge.save(kn);
    }

    private static RawItem textItem(String ext) {
        return new RawItem(ext, EntityType.MESSAGE, "text/plain", ext, "uri:" + ext,
                "sha256:" + ext, Instant.now(), Map.of("k", "v"), "body of " + ext, null,
                Map.of("title", ext), false);
    }

    private Cursor seedCursor(CursorDirection direction) {
        Cursor cursor = TestData.cursor("kn_1", "root", direction, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(cursor);
        return cursor;
    }

    @Test
    void backwardDrainPersistsEntitiesAndExhausts() {
        connector.enqueue(CursorDirection.BACKWARD,
                new GrabPage(List.of(textItem("a"), textItem("b")), "pos-1", false));
        Cursor cursor = seedCursor(CursorDirection.BACKWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(2, entities.store.size());
        entities.store.values().forEach(e -> assertEquals(EntityStatus.INGESTED, e.status()));
        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.EXHAUSTED, after.status(), "drained history is terminal");
        assertEquals("pos-1", after.position());
        assertEquals(2, knowledge.findById("kn_1").orElseThrow().stats().entities());
    }

    @Test
    void forwardCaughtUpRestsIdle() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabPage(List.of(textItem("x")), "pos-fwd", false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(CursorStatus.IDLE, cursors.store.get(cursor.id()).status(),
                "forward cursor parks IDLE until re-armed");
    }

    @Test
    void continuesAvailableWhenMorePagesRemain() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabPage(List.of(textItem("x")), "pos-1", true)); // hasMore, but only one page queued
        runner.batchesPerLease = 1; // stop after one page
        Cursor cursor = seedCursor(CursorDirection.FORWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(CursorStatus.AVAILABLE, cursors.store.get(cursor.id()).status(),
                "more pages remain -> re-pick next tick");
    }

    @Test
    void failureBelowRetryLimitStaysAvailableAndCountsRetry() {
        connector.failNext(new RuntimeException("boom"));
        Cursor cursor = seedCursor(CursorDirection.BACKWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.AVAILABLE, after.status());
        assertEquals(1, after.retry().count());
    }
}
