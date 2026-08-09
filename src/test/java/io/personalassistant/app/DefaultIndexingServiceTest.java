package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
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
    private DefaultIndexingService service;

    @BeforeEach
    void setUp() {
        cursors = new InMemoryCursorRepository();
        entities = new InMemoryEntityRepository();
        InMemoryKnowledgeRepository knowledge = new InMemoryKnowledgeRepository();
        knowledge.save(TestData.knowledge("kn_1", SourceType.LOCAL_FS, Instant.now(), Map.of()));
        ScheduleResolver schedules = new ScheduleResolver(
                new SingleConnectorRegistry(new StubConnector(SourceType.LOCAL_FS, List.of())), "1d", "");
        service = new DefaultIndexingService(
                new ForwardCursorScheduler(knowledge, cursors, schedules), entities, cursors);
    }

    /** Drive a cursor to FAILED through the real failure path rather than constructing the state. */
    private Cursor failedCursor(String knowledgeId, String iterableId, CursorDirection direction) {
        Cursor cursor = TestData.cursor(knowledgeId, iterableId, direction, SourceType.LOCAL_FS);
        cursors.insertIfAbsent(cursor);
        cursors.claim(cursor.id(), "w1", Duration.ofMinutes(5));
        cursors.recordFailure(cursor.id(), "w1", CursorStatus.FAILED, 6, "source unreachable");
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
}
