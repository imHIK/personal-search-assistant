package io.personalassistant.ingestion.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabResult;
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
        runner = new IngestionRunner(new SingleConnectorRegistry(connector), entities, cursors);
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
        // Cursors created by reconcile carry the iterable's attributes, so the runner needn't discover.
        return seedCursor(direction, Map.of("path", "/tmp", "recursive", false));
    }

    private Cursor seedCursor(CursorDirection direction, Map<String, Object> attributes) {
        Cursor cursor = TestData.cursor("kn_1", "root", attributes, direction, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(cursor);
        // Lease it to the worker first, mirroring what IngestionJob does before runLease.
        return cursors.claim(cursor.id(), "w1", java.time.Duration.ofMinutes(5)).orElseThrow();
    }

    /** Re-arm (as the scheduler would) and re-claim, so a rested cursor can be run again. */
    private Cursor reclaim(Cursor cursor) {
        cursors.armForwardCursors("kn_1");
        return cursors.claim(cursor.id(), "w1", java.time.Duration.ofMinutes(5)).orElseThrow();
    }

    @Test
    void backwardDrainPersistsEntitiesAndExhausts() {
        connector.enqueue(CursorDirection.BACKWARD,
                new GrabResult(List.of(textItem("a"), textItem("b")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.BACKWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(2, entities.store.size());
        entities.store.values().forEach(e -> assertEquals(EntityStatus.INGESTED, e.status()));
        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.EXHAUSTED, after.status(), "drained history is terminal");
        assertEquals(1L, after.position().getLong("seq", 0L));
        assertEquals(2, entities.countByKnowledge("kn_1"), "both items were persisted as entities");
    }

    @Test
    void forwardCaughtUpRestsIdle() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 2L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(CursorStatus.IDLE, cursors.store.get(cursor.id()).status(),
                "forward cursor parks IDLE until re-armed");
    }

    @Test
    void continuesAvailableWhenMorePagesRemain() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 3L)), true)); // hasMore, but only one page queued
        runner.batchesPerLease = 1; // stop after one page
        Cursor cursor = seedCursor(CursorDirection.FORWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(CursorStatus.AVAILABLE, cursors.store.get(cursor.id()).status(),
                "more pages remain -> re-pick next tick");
    }

    @Test
    void rebuildsIterableFromCursorAttributesWithoutDiscovering() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 9L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD, Map.of("path", "/data/inbox", "recursive", true));

        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(0, connector.discoverCalls,
                "self-contained cursor must not trigger discover() on the hot path");
        assertEquals("root", connector.lastGrabIterableId);
        assertEquals(Map.of("path", "/data/inbox", "recursive", true), connector.lastGrabAttributes,
                "grab receives the attributes snapshotted on the cursor");
    }

    @Test
    void failureBelowRetryLimitStaysAvailableAndCountsRetry() {
        connector.failNext(new RuntimeException("boom"));
        Cursor cursor = seedCursor(CursorDirection.BACKWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.AVAILABLE, after.status());
        assertEquals(1, after.retry().count());
        assertNotNull(after.retry().lastError(), "the failure is captured on the cursor for debugging");
        assertTrue(after.retry().lastError().contains("boom"), "lastError carries the exception message");
    }

    /**
     * B3 regression. An item re-ingested while the indexer is mid-run on its previous revision must
     * fence that indexer out. Before the fix, upsert's whole-document replace wiped the lease and
     * status, the indexer finished on the <em>old</em> text and — its write being unfenced — marked
     * the entity INDEXED with needsReindex=false. The new content then sat in Mongo, believed
     * indexed, and was permanently absent from search.
     */
    @Test
    void reIngestFencesAnIndexerRunningOnThePreviousRevision() {
        // Revision 1 lands and the indexer claims it.
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        Entity claimed = entities.claimForIndexing(1, "idx1", java.time.Duration.ofMinutes(5)).get(0);
        assertEquals(EntityStatus.INDEXING, claimed.status());

        // Revision 2 of the same externalId arrives while idx1 is still working.
        RawItem revised = new RawItem("doc", EntityType.MESSAGE, "text/plain", "doc", "uri:doc",
                "sha256:doc-v2", Instant.now(), Map.of("k", "v"), "the new body", null,
                Map.of("title", "doc"), false);
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(revised), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        Entity stored = entities.findById(claimed.id()).orElseThrow();
        assertEquals("the new body", stored.content().text(), "the new revision is what is stored");
        assertEquals(EntityStatus.INGESTED, stored.status(), "and it is back in the indexing queue");
        assertNull(stored.lease(), "the in-flight indexer's lease is dropped");

        // The stale indexer now cannot record anything — this is the actual fix.
        assertFalse(entities.markIndexed(claimed.id(), "idx1", 3, "m", Instant.now()),
                "the fenced-out indexer must not mark stale content as indexed");
        assertEquals(EntityStatus.INGESTED, entities.findById(claimed.id()).orElseThrow().status());
        assertEquals(1, entities.claimForIndexing(10, "idx2", java.time.Duration.ofMinutes(5)).size(),
                "the new revision is re-claimable, so it does reach the index");
    }

    /**
     * B5 regression: retry.count is <em>consecutive</em> failures, so a successful run must clear it.
     * Before the fix the counter accumulated for the cursor's whole lifetime — a source that hiccups
     * once a week was parked FAILED after retryLimit weeks of otherwise-successful syncs, and needed
     * direct database surgery to revive.
     */
    @Test
    void successfulRunResetsTheConsecutiveFailureStreak() {
        connector.failNext(new RuntimeException("transient"));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        assertEquals(1, cursors.store.get(cursor.id()).retry().count());

        // A clean run a while later. The cursor rests IDLE when it catches up, so getting back to it
        // goes through the scheduler's re-arm — the same path ForwardCursorScheduler drives.
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 1L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        assertEquals(0, cursors.store.get(cursor.id()).retry().count(), "a success ends the streak");
        assertNull(cursors.store.get(cursor.id()).retry().lastError(), "and clears the stale error");

        // The next failure starts a new streak at 1, not 2 — this is what keeps retryLimit meaningful.
        connector.failNext(new RuntimeException("another transient"));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        Cursor after = cursors.store.get(cursor.id());
        assertEquals(1, after.retry().count(), "streak restarts rather than accumulating");
        assertEquals(CursorStatus.AVAILABLE, after.status(), "and it is nowhere near the dead-letter limit");
    }
}
