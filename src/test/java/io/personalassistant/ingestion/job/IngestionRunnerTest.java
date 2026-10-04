package io.personalassistant.ingestion.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitedException;
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
        runner.maxDeferrals = 3;

        kn = TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), Map.of("rootPath", "/tmp"));
        knowledge.save(kn);
    }

    private static RawItem textItem(String ext) {
        return textItem(ext, "sha256:" + ext);
    }

    private static RawItem textItem(String ext, String checksum) {
        return new RawItem(ext, EntityType.MESSAGE, "text/plain", ext, "uri:" + ext,
                checksum, Instant.now(), Map.of("k", "v"), "body of " + ext, null,
                Map.of("title", ext), null, false);
    }

    private static RawItem fileItem(String ext) {
        return RawItem.file(ext, "text/plain", ext, "uri:" + ext, "sha256:" + ext, Instant.now(),
                "/scratch/" + ext + ".txt", Map.of("k", "v"), Map.of("title", ext));
    }

    private Cursor seedCursor(CursorDirection direction) {
        return seedCursor(direction, Map.of("path", "/tmp", "recursive", false));
    }

    private Cursor seedCursor(CursorDirection direction, Map<String, Object> attributes) {
        Cursor cursor = TestData.cursor("kn_1", "root", attributes, direction, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(cursor);
        return cursors.claim(cursor.id(), "w1", java.time.Duration.ofMinutes(5)).orElseThrow();
    }

    private static RateLimitedException rateLimited(Instant retryAt) {
        return new RateLimitedException(RateLimitKey.connection("conn_1"), retryAt);
    }

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
        runner.batchesPerLease = 1;
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

    @Test
    void reIngestFencesAnIndexerRunningOnThePreviousRevision() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        Entity claimed = entities.claimForIndexing(1, "idx1", java.time.Duration.ofMinutes(5)).get(0);
        assertEquals(EntityStatus.INDEXING, claimed.status());

        RawItem revised = new RawItem("doc", EntityType.MESSAGE, "text/plain", "doc", "uri:doc",
                "sha256:doc-v2", Instant.now(), Map.of("k", "v"), "the new body", null,
                Map.of("title", "doc"), null, false);
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(revised), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        Entity stored = entities.findById(claimed.id()).orElseThrow();
        assertEquals("the new body", stored.content().text(), "the new revision is what is stored");
        assertEquals(EntityStatus.INGESTED, stored.status(), "and it is back in the indexing queue");
        assertNull(stored.lease(), "the in-flight indexer's lease is dropped");

        assertFalse(entities.markIndexed(claimed.id(), "idx1", 3, "m", Instant.now()),
                "the fenced-out indexer must not mark stale content as indexed");
        assertEquals(EntityStatus.INGESTED, entities.findById(claimed.id()).orElseThrow().status());
        assertEquals(1, entities.claimForIndexing(10, "idx2", java.time.Duration.ofMinutes(5)).size(),
                "the new revision is re-claimable, so it does reach the index");
    }

    @Test
    void anUnchangedItemStillAwaitingIndexingIsNotRewritten() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});

        Entity claimed = entities.claimForIndexing(1, "idx1", java.time.Duration.ofMinutes(5)).get(0);
        Instant reopensAt = Instant.now().plusSeconds(3600);
        assertTrue(entities.markFailed(claimed.id(), "idx1", EntityStatus.INGESTED, "throttled", 4,
                reopensAt));

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        Entity stored = entities.findById(claimed.id()).orElseThrow();
        assertEquals(4, stored.retry().count(), "the deferral streak survives a poll");
        assertEquals(reopensAt, stored.retry().nextAttemptAt(), "and so does the reopening instant");
        assertTrue(entities.claimForIndexing(10, "idx2", java.time.Duration.ofMinutes(5)).isEmpty(),
                "the hold still keeps it out of the claim batch");
    }

    @Test
    void anUnchangedItemThatDeadLetteredIsRewrittenSoItGetsAnotherChance() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        Entity claimed = entities.claimForIndexing(1, "idx1", java.time.Duration.ofMinutes(5)).get(0);
        assertTrue(entities.markFailed(claimed.id(), "idx1", EntityStatus.FAILED, "boom", 6, null));

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        Entity stored = entities.findById(claimed.id()).orElseThrow();
        assertEquals(EntityStatus.INGESTED, stored.status(), "the dead letter is back in the queue");
        assertEquals(0, stored.retry().count(), "with a fresh retry budget");
    }

    @Test
    void contentIsFetchedOnlyForItemsTheRunnerDecidesToPersist() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        assertEquals(1, connector.materializeCalls, "a new item is fetched");

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        assertEquals(1, connector.materializeCalls, "an unchanged item costs no fetch");

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc", "sha256:edited")), CursorPosition.of(Map.of("seq", 3L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        assertEquals(2, connector.materializeCalls, "a changed checksum is fetched");
        assertEquals("body of doc", entities.store.values().iterator().next().content().text());
    }

    @Test
    void aDeadLetteredItemIsFetchedAgainEvenThoughItsChecksumIsUnchanged() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        Entity claimed = entities.claimForIndexing(1, "idx1", java.time.Duration.ofMinutes(5)).get(0);
        assertTrue(entities.markFailed(claimed.id(), "idx1", EntityStatus.FAILED, "boom", 6, null));

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("doc")), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        assertEquals(2, connector.materializeCalls);
    }

    @Test
    void anItemFlaggedForRefetchIsFetchedAgainDespiteAnUnchangedChecksum() {
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(fileItem("doc")), CursorPosition.of(Map.of("seq", 1L)), false));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        assertEquals(1, connector.materializeCalls);

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(fileItem("doc")), CursorPosition.of(Map.of("seq", 2L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        assertEquals(1, connector.materializeCalls, "unchanged and untouched: no fetch");

        assertEquals(1, entities.flagNeedsRefetchByKnowledge(kn.id()));
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(fileItem("doc")), CursorPosition.of(Map.of("seq", 3L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});

        assertEquals(2, connector.materializeCalls, "the flag overrides the unchanged checksum");
        Entity stored = entities.store.values().iterator().next();
        assertFalse(stored.needsRefetch(), "and is cleared by the write that used it, not left to repeat");
    }

    @Test
    void rateLimitHoldsTheCursorOutOfTheClaimBatchUntilTheWindowReopens() {
        Instant reopensAt = Instant.now().plusSeconds(600);
        connector.failNext(rateLimited(reopensAt));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);

        runner.runLease(kn, cursor, "w1", () -> {});

        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.RATE_LIMITED, after.status());
        assertEquals(reopensAt, after.retry().nextAttemptAt(), "the limiter's reopening instant is kept");
        assertEquals(1, after.retry().count(), "and it counts against the deferral budget, not retryLimit");
        assertTrue(after.retry().lastError().contains("Rate limited"));
        assertTrue(cursors.findClaimable(List.of(kn.id()), 100).isEmpty(), "the hold keeps it out of the poll batch");
    }

    @Test
    void anElapsedHoldMakesTheCursorClaimableAgain() {
        connector.failNext(rateLimited(Instant.now().minusSeconds(1)));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});

        assertEquals(List.of(cursor.id()),
                cursors.findClaimable(List.of(kn.id()), 100).stream().map(Cursor::id).toList(),
                "no sweeper flips the status — the instant simply stops excluding it");
    }

    @Test
    void deadLetteringAfterTheDeferralBudgetClearsTheHold() {
        runner.maxDeferrals = 1;
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        connector.failNext(rateLimited(Instant.now().minusSeconds(1)));
        runner.runLease(kn, cursor, "w1", () -> {});
        assertEquals(CursorStatus.RATE_LIMITED, cursors.store.get(cursor.id()).status());

        Cursor reclaimed = cursors.claim(cursor.id(), "w1", java.time.Duration.ofMinutes(5)).orElseThrow();
        connector.failNext(rateLimited(Instant.now().plusSeconds(600)));
        runner.runLease(kn, reclaimed, "w1", () -> {});

        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.FAILED, after.status(), "consecutive deferrals past the budget dead-letter");
        assertNull(after.retry().nextAttemptAt(), "a dead-lettered cursor carries no stale hold");
    }

    @Test
    void aSuccessfulRunClearsARateLimitHold() {
        connector.failNext(rateLimited(Instant.now().minusSeconds(1)));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        assertNotNull(cursors.store.get(cursor.id()).retry().nextAttemptAt());

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 1L)), false));
        runner.runLease(kn, cursors.claim(cursor.id(), "w1", java.time.Duration.ofMinutes(5)).orElseThrow(),
                "w1", () -> {});

        Cursor after = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.IDLE, after.status());
        assertNull(after.retry().nextAttemptAt(), "release() zeroes the whole retry block, hold included");
    }

    @Test
    void successfulRunResetsTheConsecutiveFailureStreak() {
        connector.failNext(new RuntimeException("transient"));
        Cursor cursor = seedCursor(CursorDirection.FORWARD);
        runner.runLease(kn, cursor, "w1", () -> {});
        assertEquals(1, cursors.store.get(cursor.id()).retry().count());

        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("x")), CursorPosition.of(Map.of("seq", 1L)), false));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        assertEquals(0, cursors.store.get(cursor.id()).retry().count(), "a success ends the streak");
        assertNull(cursors.store.get(cursor.id()).retry().lastError(), "and clears the stale error");

        connector.failNext(new RuntimeException("another transient"));
        runner.runLease(kn, reclaim(cursor), "w1", () -> {});
        Cursor after = cursors.store.get(cursor.id());
        assertEquals(1, after.retry().count(), "streak restarts rather than accumulating");
        assertEquals(CursorStatus.AVAILABLE, after.status(), "and it is nowhere near the dead-letter limit");
    }
}
