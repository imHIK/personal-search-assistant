package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.personalassistant.domain.model.enums.ReindexMode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.IndexingService;
import io.personalassistant.ingestion.job.ForwardCursorScheduler;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B6 regression. Before this, {@code FAILED} was a one-way door: {@code armForwardCursors} matches
 * only {@code IDLE}, {@code resumeByKnowledge} only {@code SUSPENDED}, and the claim filter excludes
 * {@code FAILED} entirely — so a transient outage that burned a cursor's retries stranded that
 * cursor's work until someone edited Mongo by hand.
 */
class DefaultIndexingServiceTest {

    private InMemoryCursorRepository cursors;
    private InMemoryEntityRepository entities;
    private InMemoryKnowledgeRepository knowledge;
    private StubConnector connector;
    private RefetchPolicy refetchPolicy;
    private DefaultIndexingService service;

    @BeforeEach
    void setUp() {
        cursors = new InMemoryCursorRepository();
        entities = new InMemoryEntityRepository();
        knowledge = new InMemoryKnowledgeRepository();
        knowledge.save(TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), Map.of()));
        connector = new StubConnector(SourceType.LOCAL_FS, List.of());
        SingleConnectorRegistry registry = new SingleConnectorRegistry(connector);
        ScheduleResolver schedules = new ScheduleResolver(registry, "1d", "");
        refetchPolicy = new RefetchPolicy(registry);
        service = new DefaultIndexingService(
                new ForwardCursorScheduler(knowledge, cursors, schedules), entities, cursors,
                knowledge, registry, refetchPolicy);
    }

    /** Drive a cursor to FAILED through the real failure path rather than constructing the state. */
    private Cursor failedCursor(String knowledgeId, String iterableId, CursorDirection direction) {
        Cursor cursor = TestData.cursor(knowledgeId, iterableId, direction, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(cursor);
        cursors.claim(cursor.id(), "w1", Duration.ofMinutes(5));
        cursors.recordFailure(cursor.id(), "w1", CursorStatus.FAILED, 6, "source unreachable", null);
        return cursor;
    }

    private Entity failedEntity(String id, String knowledgeId) {
        entities.upsert(TestData.ingestedText(id, knowledgeId, "ext_" + id, "text"));
        entities.claimForIndexing(10, "idx1", Duration.ofMinutes(5));
        entities.markFailed(id, "idx1", EntityStatus.FAILED, "boom", 6, null);
        return entities.findById(id).orElseThrow();
    }

    @Test
    void revivesDeadLetteredCursorsAndEntitiesWithAFreshBudget() {
        Cursor cursor = failedCursor("kn_1", "root", CursorDirection.BACKWARD);
        failedEntity("ent_1", "kn_1");
        assertTrue(cursors.findClaimable(10).isEmpty(), "precondition: nothing is claimable");
        assertTrue(entities.claimForIndexing(10, "w", Duration.ofMinutes(5)).isEmpty());

        IndexingService.RetryTrigger result = service.retryFailed("kn_1");

        assertEquals(1, result.cursorsRetried());
        assertEquals(1, result.entitiesRetried());
        assertEquals("kn_1", result.knowledgeId());

        Cursor revivedCursor = cursors.store.get(cursor.id());
        assertEquals(CursorStatus.AVAILABLE, revivedCursor.status());
        assertEquals(0, revivedCursor.retry().count(), "a fresh budget, or it dies on the first hiccup");
        assertNull(revivedCursor.retry().lastError());
        assertNull(revivedCursor.lease());

        Entity revivedEntity = entities.findById("ent_1").orElseThrow();
        assertEquals(EntityStatus.INGESTED, revivedEntity.status());
        assertEquals(0, revivedEntity.retry().count());
        assertNull(revivedEntity.index().error());

        // The point of the exercise: both are back in their work queues.
        assertEquals(1, cursors.findClaimable(10).size());
        assertEquals(1, entities.claimForIndexing(10, "w", Duration.ofMinutes(5)).size());
    }

    /** A backward cursor is exactly what {@code /sync} could never revive — hence the separate endpoint. */
    @Test
    void revivesBackwardCursorsThatAForwardSyncCouldNotReach() {
        Cursor backward = failedCursor("kn_1", "root", CursorDirection.BACKWARD);

        assertEquals(0, service.triggerSync("kn_1").cursorsArmed(), "a forward sync cannot touch it");
        assertEquals(CursorStatus.FAILED, cursors.store.get(backward.id()).status());

        assertEquals(1, service.retryFailed("kn_1").cursorsRetried());
        assertEquals(CursorStatus.AVAILABLE, cursors.store.get(backward.id()).status());
    }

    @Test
    void leavesHealthyCursorsAndOtherKnowledgesAlone() {
        Cursor healthy = TestData.cursor("kn_1", "other", CursorDirection.FORWARD, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(healthy);
        cursors.claim(healthy.id(), "w1", Duration.ofMinutes(5));
        cursors.release(healthy.id(), "w1", CursorStatus.IDLE);
        Cursor elsewhere = failedCursor("kn_2", "root", CursorDirection.FORWARD);

        IndexingService.RetryTrigger result = service.retryFailed("kn_1");

        assertEquals(0, result.cursorsRetried(), "nothing was dead-lettered in kn_1");
        assertEquals(CursorStatus.IDLE, cursors.store.get(healthy.id()).status(), "an IDLE cursor is untouched");
        assertEquals(CursorStatus.FAILED, cursors.store.get(elsewhere.id()).status(),
                "another knowledge's dead-letters are not swept up");
    }

    @Test
    void bulkReindexQueuesAKnowledgesEntitiesWithoutRefetching() {
        // The reason this exists: switching embedding model leaves the corpus half old-model vectors
        // and half new, which same-dimension does nothing to fix.
        entities.seed(TestData.ingestedText("ent_a", "kn_1", "a", "one"));
        entities.seed(TestData.ingestedText("ent_b", "kn_1", "b", "two"));
        entities.seed(TestData.ingestedText("ent_other", "kn_2", "c", "three"));

        IndexingService.ReindexTrigger result = service.reindexKnowledge("kn_1");

        assertEquals(2, result.queued());
        assertEquals(0, result.refetching(), "a REINDEX_ONLY connector fetches nothing");
        assertEquals(0, result.cursorsReset(), "and leaves the cursors where they are");
        assertTrue(entities.findById("ent_a").orElseThrow().needsReindex());
        assertTrue(entities.findById("ent_b").orElseThrow().needsReindex());
        assertFalse(entities.findById("ent_other").orElseThrow().needsReindex(),
                "another knowledge must be untouched");
    }

    @Test
    void bulkReindexSkipsAnEntityAWorkerIsMidRunOn() {
        // Flagging it would race the worker's own terminal write; a later call picks it up.
        entities.seed(TestData.ingestedText("ent_busy", "kn_1", "busy", "text"));
        entities.claimForIndexing(1, "worker-1", java.time.Duration.ofMinutes(10));

        assertEquals(0, service.reindexKnowledge("kn_1").queued());
    }

    // ---- L11: re-index of a connector whose content is a staged copy -------------------------

    /** A file-backed RawItem shaped as the connector's own walk would shape it. */
    private static RawItem refreshed(String externalId, String fileRef) {
        return RawItem.file(externalId, "text/plain", externalId, "file://" + externalId,
                "sha256:v2", Instant.now(), fileRef, Map.of("contentType", "text/plain"),
                Map.of("title", externalId, "uri", "file://" + externalId));
    }

    @Test
    void singleEntityReindexRefetchesWhenTheConnectorStagesItsContent() {
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX)
                .withFetchOne("staged", refreshed("staged", "/scratch/staged-fresh.txt"));
        entities.seed(TestData.ingestedFile("ent_f", "kn_1", "staged", "/scratch/staged-old.txt",
                "text/plain"));

        service.reindexEntity("ent_f");

        assertEquals(List.of("staged"), connector.fetchOneCalls);
        Entity stored = entities.findById("ent_f").orElseThrow();
        assertEquals("/scratch/staged-fresh.txt", stored.content().fileRef(),
                "the entity must point at the copy we just wrote, not the one that went missing");
        assertEquals("sha256:v2", stored.checksum(),
                "and carry the source's current checksum, or the next walk sees a phantom change");
        assertEquals(EntityStatus.INGESTED, stored.status(), "which is enough to re-queue it");
        assertFalse(stored.needsRefetch(), "the flag is spent by the write that used it");
    }

    @Test
    void singleEntityReindexOfInlineContentNeverFetches() {
        // Even for a staging connector: text lives in Mongo, so a fetch would buy nothing. This is
        // the Drive native-doc case — exported to text at ingest, stored, and safe.
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        entities.seed(TestData.ingestedText("ent_t", "kn_1", "inline", "body"));

        service.reindexEntity("ent_t");

        assertTrue(connector.fetchOneCalls.isEmpty());
        assertTrue(entities.findById("ent_t").orElseThrow().needsReindex());
    }

    @Test
    void singleEntityReindexTombstonesWhatIsGoneAtTheSource() {
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX); // no fetchOne result => empty
        entities.seed(TestData.ingestedFile("ent_g", "kn_1", "gone", "/scratch/gone.txt", "text/plain"));

        service.reindexEntity("ent_g");

        assertEquals(EntityStatus.DELETED, entities.findById("ent_g").orElseThrow().status(),
                "re-indexing content the source no longer has would keep a stale hit alive");
    }

    @Test
    void bulkReindexFlagsTheStagedFilesAndRewindsTheCursorsThatReachThem() {
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        entities.seed(TestData.ingestedFile("ent_f1", "kn_1", "f1", "/scratch/a.txt", "text/plain"));
        entities.seed(TestData.ingestedText("ent_t1", "kn_1", "t1", "inline"));
        Cursor forward = advanced(TestData.cursor("kn_1", "root", CursorDirection.FORWARD, SourceType.LOCAL_FS));

        IndexingService.ReindexTrigger result = service.reindexKnowledge("kn_1");

        assertEquals(2, result.queued());
        assertEquals(1, result.refetching(), "only the file-backed entity needs bytes again");
        assertEquals(1, result.cursorsReset());
        assertTrue(entities.findById("ent_f1").orElseThrow().needsRefetch());
        assertFalse(entities.findById("ent_t1").orElseThrow().needsRefetch(),
                "inline text would cost a fetch and gain nothing");
        Cursor rewound = cursors.store.get(forward.id());
        assertTrue(rewound.position().isStart(),
                "without the rewind the high-water floor never offers an unmodified file again");
        assertEquals(CursorStatus.AVAILABLE, rewound.status());
    }

    @Test
    void bulkReindexLeavesRetiredCursorsParked() {
        // Their iterable is gone at the source; re-walking would fail, or resurrect parked data.
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        entities.seed(TestData.ingestedFile("ent_f2", "kn_1", "f2", "/scratch/b.txt", "text/plain"));
        Cursor gone = advanced(TestData.cursor("kn_1", "old", CursorDirection.FORWARD, SourceType.LOCAL_FS));
        cursors.retire(gone.id());

        assertEquals(0, service.reindexKnowledge("kn_1").cursorsReset());
        assertEquals(CursorStatus.RETIRED, cursors.store.get(gone.id()).status());
        assertFalse(cursors.store.get(gone.id()).position().isStart());
    }

    @Test
    void bulkReindexDoesNotRestartABackfillTheUserTurnedOffPartWay() {
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        knowledge.save(withBackfill(knowledge.findById("kn_1").orElseThrow(), false));
        entities.seed(TestData.ingestedFile("ent_f3", "kn_1", "f3", "/scratch/c.txt", "text/plain"));
        Cursor backward = advanced(TestData.cursor("kn_1", "root", CursorDirection.BACKWARD, SourceType.LOCAL_FS));

        assertEquals(0, service.reindexKnowledge("kn_1").cursorsReset(),
                "rewinding it would silently resume a walk the user stopped");
        assertFalse(cursors.store.get(backward.id()).position().isStart());
    }

    @Test
    void bulkReindexRewindsADrainedBackfillEvenWithBackfillOff() {
        // EXHAUSTED means it already covered its whole range, so replaying it adds no new history —
        // it is just how the entities below the anchor are reached again.
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        knowledge.save(withBackfill(knowledge.findById("kn_1").orElseThrow(), false));
        entities.seed(TestData.ingestedFile("ent_f4", "kn_1", "f4", "/scratch/d.txt", "text/plain"));
        Cursor drained = advanced(
                TestData.cursor("kn_1", "root", CursorDirection.BACKWARD, SourceType.LOCAL_FS),
                CursorStatus.EXHAUSTED);

        assertEquals(1, service.reindexKnowledge("kn_1").cursorsReset());
        assertTrue(cursors.store.get(drained.id()).position().isStart());
    }

    @Test
    void refetchOnReindexNeverRestoresTheOldTrustTheStoredPathBehaviour() {
        refetchPolicy.mode = "never";
        connector.withReindexMode(ReindexMode.FETCH_AND_REINDEX);
        entities.seed(TestData.ingestedFile("ent_f5", "kn_1", "f5", "/scratch/e.txt", "text/plain"));

        service.reindexEntity("ent_f5");
        IndexingService.ReindexTrigger result = service.reindexKnowledge("kn_1");

        assertTrue(connector.fetchOneCalls.isEmpty());
        assertEquals(0, result.refetching());
        assertEquals(0, result.cursorsReset());
    }

    /** Walk a cursor off its start position, so a rewind is observable. */
    private Cursor advanced(Cursor cursor) {
        return advanced(cursor, CursorStatus.IDLE);
    }

    private Cursor advanced(Cursor cursor, CursorStatus resting) {
        cursors.insertIfAbsent(cursor);
        cursors.claim(cursor.id(), "w0", Duration.ofMinutes(5));
        cursors.advancePosition(cursor.id(), "w0",
                CursorPosition.of(Map.of("lastModifiedMillis", 99L)), 1,
                Instant.now(), Instant.now().plusSeconds(300));
        cursors.release(cursor.id(), "w0", resting);
        return cursor;
    }

    private static Knowledge withBackfill(Knowledge kn, boolean enabled) {
        Knowledge.Config c = kn.config();
        return kn.withEdits(kn.name(), kn.connectorDetails(), kn.inputs(),
                new Knowledge.Config(c.scheduleSettings(), c.webhookSettings(),
                        new Knowledge.Backfill(enabled), c.chunking(), c.retention()),
                Instant.now());
    }
}
