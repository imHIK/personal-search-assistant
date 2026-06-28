package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.DiscoveryOutcome;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.testsupport.InMemoryCursorRepository;
import io.personalassistant.testsupport.InMemoryDiscoveryStatusRepository;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.RecordingSearchIndex;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultKnowledgeServiceTest {

    private InMemoryKnowledgeRepository knowledge;
    private InMemoryCursorRepository cursors;
    private InMemoryEntityRepository entities;
    private RecordingSearchIndex index;
    private InMemoryDiscoveryStatusRepository discovery;
    private StubConnector connector;
    private DefaultKnowledgeService service;

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
        service = new DefaultKnowledgeService(knowledge, cursors,
                entities, new SingleConnectorRegistry(connector), index, discovery);
    }

    private Knowledge addKnowledge() {
        return service.add(new KnowledgeService.NewKnowledge(
                "team slack", SourceType.SLACK, Map.of(), Map.of(), null));
    }

    @Test
    void activationCreatesBackwardAndForwardCursorsPerIterable() {
        Knowledge kn = addKnowledge();
        // one iterable × {backward, forward} = 2 cursors
        assertEquals(2, cursors.findByKnowledge(kn.id()).size());
    }

    @Test
    void discoveryFailureParksKnowledgeInErrorWithReason() {
        connector.failDiscoveryWith(new IllegalStateException("source unreachable"));

        Knowledge kn = addKnowledge();

        Knowledge stored = knowledge.findById(kn.id()).orElseThrow();
        assertEquals(KnowledgeStatus.ERROR, stored.status(), "a failed activation lands in ERROR");
        assertNotNull(stored.lastError(), "the failure reason is captured for debugging");
        assertTrue(stored.lastError().contains("source unreachable"), "lastError carries the cause");
        assertEquals(0, cursors.findByKnowledge(kn.id()).size(), "no cursors are created on failure");
    }

    @Test
    void reconcileCreatesCursorsForNewlyDiscoveredIterables() {
        Knowledge kn = addKnowledge();
        assertEquals(2, cursors.findByKnowledge(kn.id()).size());

        // A new channel appears at the source after activation.
        connector.addIterable(new SourceIterable("chan_b", "B", Map.of()));

        int added = service.reconcileCursors(kn.id());
        assertEquals(2, added, "new iterable → one backward + one forward cursor");
        assertEquals(4, cursors.findByKnowledge(kn.id()).size());

        // Idempotent: a second reconcile with no new iterables adds nothing.
        assertEquals(0, service.reconcileCursors(kn.id()));
        assertEquals(4, cursors.findByKnowledge(kn.id()).size());
    }

    @Test
    void createdCursorsSnapshotIterableAttributesForGrab() {
        Knowledge kn = addKnowledge();
        // A channel with grab-relevant attributes appears; reconcile should snapshot them onto the cursors.
        connector.addIterable(new SourceIterable("chan_b", "B", Map.of("channelId", "C999", "isPrivate", true)));
        service.reconcileCursors(kn.id());

        Cursor b = cursors.findByKnowledge(kn.id()).stream()
                .filter(c -> c.iterableId().equals("chan_b")).findFirst().orElseThrow();
        assertEquals(Map.of("channelId", "C999", "isPrivate", true), b.attributes(),
                "the cursor carries the iterable's attributes so the runner needn't re-discover");
    }

    @Test
    void deletedIterableRetiresItsCursorsAndPurgesData() {
        Knowledge kn = addKnowledge(); // chan_a + its cursors
        entities.upsert(TestData.entityInIterable("ent_a", kn.id(), "chan_a", "a1"));
        // a sibling iterable whose data must survive the prune
        connector.addIterable(new SourceIterable("chan_keep", "K", Map.of()));
        service.reconcileCursors(kn.id());
        entities.upsert(TestData.entityInIterable("ent_k", kn.id(), "chan_keep", "k1"));

        // chan_a is deleted at the source
        connector.removeIterable("chan_a");
        int created = service.reconcileCursors(kn.id());

        assertEquals(0, created);
        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                        .filter(c -> c.iterableId().equals("chan_a"))
                        .allMatch(c -> c.status() == CursorStatus.RETIRED),
                "both of the deleted iterable's cursors are retired");
        assertTrue(index.deletedIterables.contains(kn.id() + "/chan_a"), "its chunks are bulk-deleted");
        assertTrue(entities.store.values().stream().noneMatch(e -> e.iterableId().equals("chan_a")),
                "its entities are purged");
        assertTrue(entities.store.values().stream().anyMatch(e -> e.iterableId().equals("chan_keep")),
                "a sibling iterable's data is untouched");
    }

    @Test
    void reappearedIterableRevivesRetiredCursorsAndRefreshesAttributes() {
        Knowledge kn = addKnowledge();
        connector.removeIterable("chan_a");
        service.reconcileCursors(kn.id()); // retires chan_a's cursors
        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                .allMatch(c -> c.status() == CursorStatus.RETIRED));

        // chan_a comes back, now with grab-relevant attributes
        connector.addIterable(new SourceIterable("chan_a", "A", Map.of("channelId", "C-new")));
        service.reconcileCursors(kn.id());

        List<Cursor> revived = cursors.findByKnowledge(kn.id());
        assertTrue(revived.stream().allMatch(c -> c.status() == CursorStatus.AVAILABLE),
                "a reappeared iterable revives its retired cursors");
        assertTrue(revived.stream().allMatch(c -> c.position().isStart()),
                "revived cursors restart from the top (their data was purged)");
        assertTrue(revived.stream().allMatch(c -> c.attributes().equals(Map.of("channelId", "C-new"))),
                "revival refreshes the snapshotted attributes");
    }

    @Test
    void reconcileNoOpsForInactiveKnowledge() {
        Knowledge kn = addKnowledge();
        service.pause(kn.id());
        connector.addIterable(new SourceIterable("chan_b", "B", Map.of()));
        assertEquals(0, service.reconcileCursors(kn.id()), "paused knowledge is not reconciled");
    }

    @Test
    void pauseParksClaimableCursorsSoTheyStopBeingPicked() {
        Knowledge kn = addKnowledge();
        assertEquals(2, cursors.findClaimable(100).size(), "fresh cursors are claimable");

        service.pause(kn.id());

        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                        .allMatch(c -> c.status() == CursorStatus.SUSPENDED),
                "pausing parks the knowledge's cursors");
        assertEquals(0, cursors.findClaimable(100).size(),
                "parked cursors no longer pollute the claim batch");
    }

    @Test
    void resumeReArmsParkedCursors() {
        Knowledge kn = addKnowledge();
        service.pause(kn.id());

        service.resume(kn.id());

        assertTrue(cursors.findByKnowledge(kn.id()).stream()
                        .allMatch(c -> c.status() == CursorStatus.AVAILABLE),
                "resuming re-arms parked cursors");
        assertEquals(2, cursors.findClaimable(100).size(), "re-armed cursors are claimable again");
    }

    @Test
    void activationRecordsDiscoveryStatusPerGrabberDirection() {
        Knowledge kn = addKnowledge();

        // SLACK supports both directions and backfill is on by default → a forward + a backward grabber.
        assertEquals(2, discovery.findByKnowledge(kn.id()).size(), "one discovery record per grabber");

        DiscoveryStatus fwd = discovery.find(kn.id(), CursorDirection.FORWARD).orElseThrow();
        DiscoveryStatus bwd = discovery.find(kn.id(), CursorDirection.BACKWARD).orElseThrow();
        assertEquals(CursorDirection.FORWARD, fwd.direction(), "status is keyed per grabber direction");
        assertEquals(DiscoveryOutcome.OK, fwd.lastOutcome());
        assertEquals(DiscoveryTrigger.ACTIVATION, fwd.lastTrigger());
        assertEquals(1, fwd.iterablesFound(), "one iterable was discovered");
        assertEquals(1, fwd.lastCounts().created(), "the forward grabber created one cursor");
        assertEquals(1, bwd.lastCounts().created(), "the backward grabber created one cursor");
        assertEquals(1, fwd.runCount());
        assertEquals(0, fwd.failureCount());
        assertNotNull(fwd.lastRunAt());
    }

    @Test
    void reconcileUpdatesDiscoveryStatusAndAccumulatesRunCount() {
        Knowledge kn = addKnowledge();
        connector.addIterable(new SourceIterable("chan_b", "B", Map.of()));

        service.reconcileCursors(kn.id());

        DiscoveryStatus fwd = discovery.find(kn.id(), CursorDirection.FORWARD).orElseThrow();
        assertEquals(DiscoveryTrigger.RECONCILE, fwd.lastTrigger());
        assertEquals(2, fwd.iterablesFound(), "discovery now sees both iterables");
        assertEquals(1, fwd.lastCounts().created(), "the new iterable added one forward cursor");
        assertEquals(2, fwd.runCount(), "activation + one reconcile");
        assertEquals(0, fwd.failureCount());
    }

    @Test
    void discoveryFailureIsRecordedPerDirectionWithoutClobberingLastGoodSnapshot() {
        Knowledge kn = addKnowledge(); // one OK activation run on record per direction

        connector.failDiscoveryWith(new IllegalStateException("source unreachable"));
        // discover throws; reconcile records the FAILED run (per direction) then rethrows
        assertThrows(IllegalStateException.class, () -> service.reconcileCursors(kn.id()));

        DiscoveryStatus fwd = discovery.find(kn.id(), CursorDirection.FORWARD).orElseThrow();
        assertEquals(DiscoveryOutcome.FAILED, fwd.lastOutcome());
        assertTrue(fwd.lastError().contains("source unreachable"), "the failure reason is captured");
        assertEquals(2, fwd.runCount(), "the failed run is counted");
        assertEquals(1, fwd.failureCount());
        assertEquals(1, fwd.iterablesFound(), "a failure keeps the last good iterable count");
    }

    @Test
    void deleteRemovesDiscoveryStatus() {
        Knowledge kn = addKnowledge();
        assertTrue(discovery.find(kn.id(), CursorDirection.FORWARD).isPresent());

        service.delete(kn.id());

        assertTrue(discovery.findByKnowledge(kn.id()).isEmpty(), "teardown purges the discovery records");
    }

}
