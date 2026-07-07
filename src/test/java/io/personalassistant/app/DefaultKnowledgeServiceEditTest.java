package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgePatch;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.GrabResult;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.job.IngestionRunner;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryDiscoveryStatusRepository;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.RecordingSearchIndex;
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
 * Phase 1 of the knowledge-edit design ({@code docs/knowledge-edit-design.md} §8): config vs.
 * provisioning routing, immutable-type / DELETED guards, park-don't-purge on shrink, the
 * membership-signature re-walk, and generation stamping. No deletion is exercised — that is Phase 2.
 */
class DefaultKnowledgeServiceEditTest {

    private InMemoryKnowledgeRepository knowledge;
    private InMemoryCursorRepository cursors;
    private InMemoryEntityRepository entities;
    private RecordingSearchIndex index;
    private InMemoryDiscoveryStatusRepository discovery;
    private StubConnector connector;
    private DefaultKnowledgeService service;
    private IngestionRunner runner;

    @BeforeEach
    void setUp() {
        knowledge = new InMemoryKnowledgeRepository();
        cursors = new InMemoryCursorRepository();
        entities = new InMemoryEntityRepository();
        index = new RecordingSearchIndex();
        discovery = new InMemoryDiscoveryStatusRepository();
        connector = new StubConnector(SourceType.SLACK,
                List.of(new SourceIterable("chan_a", "A", Map.of())))
                .withDynamicIterables(true);
        SingleConnectorRegistry registry = new SingleConnectorRegistry(connector);
        // SLACK stub needs no connection, so a trivial resolver suffices here.
        io.personalassistant.ingestion.connector.ConnectionResolver connections = kn -> null;
        service = new DefaultKnowledgeService(knowledge, cursors, entities, registry, connections, index, discovery);

        runner = new IngestionRunner(registry, entities, cursors);
        runner.batchesPerLease = 50;
        runner.maxItemsPerBatch = 100;
        runner.leaseSeconds = 60;
        runner.retryLimit = 2;
    }

    private Knowledge add(Map<String, Object> inputs) {
        return service.add(new KnowledgeService.NewKnowledge(
                "team slack", SourceType.SLACK, null, Map.of(), inputs, null));
    }

    private Cursor cursorFor(String knowledgeId, String iterableId, CursorDirection direction) {
        return cursors.findByKnowledge(knowledgeId).stream()
                .filter(c -> c.iterableId().equals(iterableId) && c.direction() == direction)
                .findFirst().orElseThrow();
    }

    private static RawItem textItem(String externalId) {
        return new RawItem(externalId, EntityType.MESSAGE, "text/plain", externalId, "uri:" + externalId,
                "sha256:" + externalId, Instant.now(), Map.of("k", "v"), "body of " + externalId, null,
                Map.of("title", externalId), false);
    }

    // ---- §8.1 config-only edit ---------------------------------------------------------------

    @Test
    void configOnlyEditIsInPlaceWithNoSourceCalls() {
        Knowledge kn = add(Map.of());
        int verifyBefore = connector.verifyCalls;
        int discoverBefore = connector.discoverCalls;

        Knowledge updated = service.update(kn.id(), KnowledgePatch.builder().name("renamed").build());

        assertEquals("renamed", updated.name());
        assertEquals(KnowledgeStatus.ACTIVE, updated.status());
        assertEquals(verifyBefore, connector.verifyCalls, "a config edit re-verifies nothing");
        assertEquals(discoverBefore, connector.discoverCalls, "a config edit re-discovers nothing");
        assertEquals(2, cursors.findByKnowledge(kn.id()).size(), "cursors are untouched");
    }

    @Test
    void cadenceChangeClearsNextSyncDueAt() {
        Knowledge kn = add(Map.of());
        knowledge.updateNextSyncDueAt(kn.id(), Instant.now().plusSeconds(3600));

        service.update(kn.id(), KnowledgePatch.builder().interval("15m").build());

        assertEquals(null, knowledge.findById(kn.id()).orElseThrow().nextSyncDueAt(),
                "a cadence change clears nextSyncDueAt so the scheduler re-resolves it (due now)");
    }

    @Test
    void enablingScheduleReArmsForwardCursors() {
        Knowledge kn = add(Map.of()); // add defaults scheduleEnabled=false
        // Simulate a forward cursor that has caught up and is waiting for the scheduler.
        Cursor fwd = cursorFor(kn.id(), "chan_a", CursorDirection.FORWARD);
        cursors.store.put(fwd.id(), new Cursor(fwd.id(), fwd.knowledgeId(), fwd.iterableId(),
                fwd.attributes(), fwd.direction(), fwd.position(), CursorStatus.IDLE, null,
                fwd.retry(), fwd.stats(), fwd.scope()));

        service.update(kn.id(), KnowledgePatch.builder().scheduleEnabled(true).build());

        assertEquals(CursorStatus.AVAILABLE, cursors.store.get(fwd.id()).status(),
                "enabling the schedule triggers a forward re-arm (IDLE → AVAILABLE)");
    }

    // ---- §8.2 provisioning edit (inputs): park-don't-purge on shrink -------------------------

    @Test
    void inputsEditParksMissingIterablesButKeepsTheirData() {
        Knowledge kn = add(Map.of()); // chan_a
        connector.addIterable(new SourceIterable("chan_b", "B", Map.of()));
        service.reconcileCursors(kn.id()); // chan_a + chan_b cursors
        entities.upsert(TestData.entityInIterable("ent_a", kn.id(), "chan_a", "a1"));
        entities.upsert(TestData.entityInIterable("ent_b", kn.id(), "chan_b", "b1"));
        int verifyBefore = connector.verifyCalls;
        int discoverBefore = connector.discoverCalls;

        connector.removeIterable("chan_a"); // narrowed scope: chan_a no longer discovered
        service.update(kn.id(), KnowledgePatch.builder().inputs(Map.of("q", "new")).build());

        assertTrue(connector.verifyCalls > verifyBefore, "a provisioning edit re-verifies");
        assertTrue(connector.discoverCalls > discoverBefore, "a provisioning edit re-discovers");
        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                        .filter(c -> c.iterableId().equals("chan_a"))
                        .allMatch(c -> c.status() == CursorStatus.RETIRED),
                "the disappeared iterable's cursors are parked (RETIRED)");
        assertTrue(entities.store.values().stream().anyMatch(e -> e.iterableId().equals("chan_a")),
                "park-don't-purge: the disappeared iterable's entities are KEPT");
        assertTrue(index.deletedIterables.isEmpty(), "no chunk purge happens on an edit shrink");
        assertEquals(KnowledgeStatus.ACTIVE, knowledge.findById(kn.id()).orElseThrow().status());
    }

    // ---- §8.3 provisioning edit (auth) -------------------------------------------------------

    @Test
    void authEditReVerifiesAndReDiscoversWithoutBumpingGeneration() {
        Knowledge kn = add(Map.of());
        int verifyBefore = connector.verifyCalls;
        int discoverBefore = connector.discoverCalls;

        Knowledge updated = service.update(kn.id(),
                KnowledgePatch.builder().auth(Map.of("token", "rotated")).build());

        assertTrue(connector.verifyCalls > verifyBefore, "an auth rotation re-verifies");
        assertTrue(connector.discoverCalls > discoverBefore, "an auth rotation re-discovers (scope may have changed)");
        assertEquals(Map.of("token", "rotated"), updated.connectorDetails().auth(), "new auth is persisted");
        assertEquals(KnowledgeStatus.ACTIVE, updated.status());
        assertEquals(0L, updated.syncGeneration(), "auth alone does not move membership → no generation bump");
    }

    // ---- §8.4 / §8.5 guards ------------------------------------------------------------------

    @Test
    void changingImmutableTypeIsRejectedAndMutatesNothing() {
        Knowledge kn = add(Map.of());

        assertThrows(IllegalArgumentException.class, () ->
                service.update(kn.id(), KnowledgePatch.builder().type(SourceType.LOCAL_FS).build()));

        assertEquals(SourceType.SLACK, knowledge.findById(kn.id()).orElseThrow().connectorDetails().type());
    }

    @Test
    void editingDeletedKnowledgeIsRejected() {
        Knowledge kn = add(Map.of());
        // delete() would drop the record entirely; store a DELETED record to exercise the guard.
        knowledge.save(knowledge.findById(kn.id()).orElseThrow().withStatus(KnowledgeStatus.DELETED));

        assertThrows(IllegalStateException.class, () ->
                service.update(kn.id(), KnowledgePatch.builder().name("nope").build()));

        assertEquals("team slack", knowledge.findById(kn.id()).orElseThrow().name(), "nothing was mutated");
    }

    @Test
    void unknownIdIsRejected() {
        assertThrows(java.util.NoSuchElementException.class, () ->
                service.update("kn_missing", KnowledgePatch.builder().name("x").build()));
    }

    // ---- §8.6 membership signature drives the re-walk ----------------------------------------

    @Test
    void reWalkHappensOnlyWhenTheMembershipSignatureChanges() {
        connector.withMembershipKeys("fileTypes"); // only fileTypes affects membership; label is cosmetic
        Knowledge kn = add(Map.of("fileTypes", "pdf,docx", "label", "Docs"));

        // Advance the forward cursor and exhaust the backward one, so a reset is observable.
        Cursor fwd = cursorFor(kn.id(), "chan_a", CursorDirection.FORWARD);
        Cursor bwd = cursorFor(kn.id(), "chan_a", CursorDirection.BACKWARD);
        putStatusAndPosition(fwd, CursorStatus.AVAILABLE, CursorPosition.of(Map.of("seq", 5L)));
        putStatusAndPosition(bwd, CursorStatus.EXHAUSTED, CursorPosition.of(Map.of("seq", 9L)));

        // Cosmetic edit: label changes, fileTypes doesn't → signature unchanged → NO re-walk.
        service.update(kn.id(),
                KnowledgePatch.builder().inputs(Map.of("fileTypes", "pdf,docx", "label", "Files")).build());
        assertFalse(cursors.store.get(fwd.id()).position().isStart(), "a cosmetic edit does not rewind cursors");
        assertEquals(0L, knowledge.findById(kn.id()).orElseThrow().syncGeneration(), "no membership move → no bump");

        // Membership edit: fileTypes narrows → signature changes → re-walk.
        service.update(kn.id(),
                KnowledgePatch.builder().inputs(Map.of("fileTypes", "pdf", "label", "Files")).build());
        assertTrue(cursors.store.get(fwd.id()).position().isStart(), "the forward cursor is rewound to start");
        assertEquals(CursorStatus.AVAILABLE, cursors.store.get(bwd.id()).status(),
                "the backward cursor is re-armed from EXHAUSTED to re-cover history under the new rule");
        assertTrue(cursors.store.get(bwd.id()).position().isStart());
        assertEquals(1L, knowledge.findById(kn.id()).orElseThrow().syncGeneration(),
                "a membership-affecting edit bumps the sync generation");
    }

    // ---- §8.7 / §8.8 the async re-walk re-ingests adds and stamps the generation -------------

    @Test
    void reWalkReIngestsNewMatchesAndStampsGenerationOnSeenAndSkippedEntities() {
        connector.withMembershipKeys("fileTypes");
        Knowledge kn = add(Map.of("fileTypes", "pdf"));
        // A pre-existing, already-indexed match that the re-walk will re-see unchanged (skip path).
        entities.upsert(TestData.entityInIterable("ent_keep", kn.id(), "chan_a", "keep"));
        entities.markIndexed("ent_keep", 1, "model", Instant.now());

        // Widen fileTypes: pdf → pdf,txt. Signature changes → generation bumps, cursors rewind.
        service.update(kn.id(), KnowledgePatch.builder().inputs(Map.of("fileTypes", "pdf,txt")).build());
        long generation = knowledge.findById(kn.id()).orElseThrow().syncGeneration();
        assertEquals(1L, generation);

        // Drive the async re-walk: the forward cursor (rewound to start) re-emits the unchanged match
        // ("keep") plus a newly-matching txt item ("new_txt").
        Cursor fwd = cursorFor(kn.id(), "chan_a", CursorDirection.FORWARD);
        Cursor leased = cursors.claim(fwd.id(), "w1", Duration.ofMinutes(5)).orElseThrow();
        connector.enqueue(CursorDirection.FORWARD,
                new GrabResult(List.of(textItem("keep"), textItem("new_txt")),
                        CursorPosition.of(Map.of("seq", 1L)), false));

        runner.runLease(knowledge.findById(kn.id()).orElseThrow(), leased, "w1", () -> {});

        // The previously-missed add is now ingested.
        var newItem = entities.findByKnowledgeAndExternalId(kn.id(), "new_txt").orElseThrow();
        assertEquals(EntityStatus.INGESTED, newItem.status(), "the newly-matching item is re-ingested");
        assertEquals(generation, newItem.lastSeenGeneration(), "a walked (upserted) entity is stamped at the new generation");

        // The unchanged, already-INDEXED entity is skipped by change detection but still stamped.
        var kept = entities.findByKnowledgeAndExternalId(kn.id(), "keep").orElseThrow();
        assertEquals(EntityStatus.INDEXED, kept.status(), "an unchanged entity is not rewritten");
        assertEquals(generation, kept.lastSeenGeneration(),
                "the skip-unchanged path still stamps the generation, so a valid file isn't left looking stale");
    }

    // ---- §8.9 status matrix ------------------------------------------------------------------

    @Test
    void errorKnowledgeRecoversToActiveOnASuccessfulEdit() {
        connector.failDiscoveryWith(new IllegalStateException("source down"));
        Knowledge kn = add(Map.of()); // discovery fails → ERROR, no cursors
        assertEquals(KnowledgeStatus.ERROR, knowledge.findById(kn.id()).orElseThrow().status());
        assertEquals(0, cursors.findByKnowledge(kn.id()).size());

        connector.failDiscoveryWith(null); // source recovers
        Knowledge recovered = service.update(kn.id(),
                KnowledgePatch.builder().auth(Map.of("token", "fixed")).build());

        assertEquals(KnowledgeStatus.ACTIVE, recovered.status(), "a successful provisioning edit is the recovery path");
        assertEquals(2, cursors.findByKnowledge(kn.id()).size(), "cursors are (re)created on recovery");
    }

    @Test
    void pausedKnowledgeStaysParkedAfterAProvisioningEdit() {
        Knowledge kn = add(Map.of());
        service.pause(kn.id());

        service.update(kn.id(), KnowledgePatch.builder().inputs(Map.of("q", "changed")).build());

        assertEquals(KnowledgeStatus.PAUSED, knowledge.findById(kn.id()).orElseThrow().status(),
                "a paused knowledge is edited but not auto-resumed");
        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                        .noneMatch(c -> c.status() == CursorStatus.AVAILABLE),
                "its cursors (including any re-walked ones) stay parked so the loop won't pick them up");
    }

    private void putStatusAndPosition(Cursor c, CursorStatus status, CursorPosition position) {
        cursors.store.put(c.id(), new Cursor(c.id(), c.knowledgeId(), c.iterableId(), c.attributes(),
                c.direction(), position, status, null, c.retry(), c.stats(), c.scope()));
    }
}
