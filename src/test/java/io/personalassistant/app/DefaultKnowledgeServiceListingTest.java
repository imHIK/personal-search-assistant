package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read-only listing endpoints that back the console's entity browser and sync-progress view.
 * Split from {@link DefaultKnowledgeServiceTest} (lifecycle) and {@code ...EditTest} (patching) so
 * each class stays about one concern.
 */
class DefaultKnowledgeServiceListingTest {

    private InMemoryKnowledgeRepository knowledge;
    private InMemoryCursorRepository cursors;
    private InMemoryEntityRepository entities;
    private DefaultKnowledgeService service;

    @BeforeEach
    void setUp() {
        knowledge = new InMemoryKnowledgeRepository();
        cursors = new InMemoryCursorRepository();
        entities = new InMemoryEntityRepository();
        RecordingSearchIndex index = new RecordingSearchIndex();
        InMemoryDiscoveryStatusRepository discovery = new InMemoryDiscoveryStatusRepository();
        StubConnector connector = new StubConnector(SourceType.SLACK,
                List.of(new SourceIterable("chan_a", "A", Map.of())));
        io.personalassistant.ingestion.connector.ConnectionResolver connections = kn -> null;
        service = new DefaultKnowledgeService(knowledge, cursors, entities,
                new SingleConnectorRegistry(connector), connections, index, discovery);
    }

    /** Persist a knowledge directly — these tests exercise reads, not the activation path. */
    private Knowledge storedKnowledge(String id) {
        Knowledge kn = TestData.knowledge(id, SourceType.SLACK, Instant.now(), Map.of());
        knowledge.save(kn);
        return kn;
    }

    /** An entity with an explicit updatedAt, so ordering assertions are deterministic. */
    private Entity entity(String id, String knowledgeId, EntityStatus status, Instant updatedAt) {
        return new Entity(id, knowledgeId, "chan_a", EntityType.MESSAGE, "ext_" + id,
                Map.of(), Entity.Content.ofText("body"),
                Map.of("title", "Title " + id, "uri", "test://" + id),
                "sha256:" + id, status, false, Entity.IndexInfo.empty(), null,
                Entity.Retry.zero(), updatedAt, updatedAt, 0L);
    }

    // ---- entity listing ----------------------------------------------------------------------

    @Test
    void listsEntitiesNewestFirstWithTitleAndUri() {
        Knowledge kn = storedKnowledge("kn_1");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        entities.seed(entity("ent_old", kn.id(), EntityStatus.INDEXED, t0));
        entities.seed(entity("ent_mid", kn.id(), EntityStatus.INDEXED, t0.plusSeconds(60)));
        entities.seed(entity("ent_new", kn.id(), EntityStatus.INGESTED, t0.plusSeconds(120)));

        List<EntitySummary> items = service.listEntities(kn.id(), null, 50, 0).items();

        assertEquals(List.of("ent_new", "ent_mid", "ent_old"),
                items.stream().map(EntitySummary::id).toList(), "newest updatedAt first");
        assertEquals("Title ent_new", items.get(0).title(), "title is lifted from metadata");
        assertEquals("test://ent_new", items.get(0).uri(), "uri is lifted from metadata");
    }

    @Test
    void paginatesWithLimitAndOffsetKeepingTotalStable() {
        Knowledge kn = storedKnowledge("kn_1");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            entities.seed(entity("ent_" + i, kn.id(), EntityStatus.INDEXED, t0.plusSeconds(i * 60L)));
        }

        KnowledgeService.EntityPage first = service.listEntities(kn.id(), null, 2, 0);
        KnowledgeService.EntityPage second = service.listEntities(kn.id(), null, 2, 2);

        assertEquals(2, first.items().size());
        assertEquals(1, second.items().size());
        assertEquals(3, first.total(), "total counts all matches, not the page");
        assertEquals(3, second.total(), "total is stable across pages");
        assertTrue(first.items().stream().map(EntitySummary::id)
                        .noneMatch(id -> second.items().stream().anyMatch(s -> s.id().equals(id))),
                "pages do not overlap");
    }

    @Test
    void filtersByStatusAndCountsOnlyTheFilteredTotal() {
        Knowledge kn = storedKnowledge("kn_1");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        entities.seed(entity("ent_a", kn.id(), EntityStatus.INDEXED, t0));
        entities.seed(entity("ent_b", kn.id(), EntityStatus.FAILED, t0.plusSeconds(60)));
        entities.seed(entity("ent_c", kn.id(), EntityStatus.FAILED, t0.plusSeconds(120)));

        KnowledgeService.EntityPage page = service.listEntities(kn.id(), EntityStatus.FAILED, 50, 0);

        assertEquals(2, page.items().size());
        assertEquals(2, page.total(), "total reflects the status filter, not the whole knowledge");
        assertTrue(page.items().stream().allMatch(s -> s.status() == EntityStatus.FAILED));
    }

    @Test
    void clampsLimitAndRejectsNegativeOffset() {
        Knowledge kn = storedKnowledge("kn_1");

        assertEquals(50, service.listEntities(kn.id(), null, 0, 0).limit(), "limit <= 0 falls back to 50");
        assertEquals(200, service.listEntities(kn.id(), null, 9999, 0).limit(), "limit is capped at 200");
        assertThrows(IllegalArgumentException.class, () -> service.listEntities(kn.id(), null, 50, -1));
    }

    @Test
    void excludesOtherKnowledgesEntities() {
        Knowledge mine = storedKnowledge("kn_1");
        Knowledge other = storedKnowledge("kn_2");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        entities.seed(entity("ent_mine", mine.id(), EntityStatus.INDEXED, t0));
        entities.seed(entity("ent_other", other.id(), EntityStatus.INDEXED, t0.plusSeconds(60)));

        KnowledgeService.EntityPage page = service.listEntities(mine.id(), null, 50, 0);

        assertEquals(1, page.total());
        assertEquals("ent_mine", page.items().get(0).id());
    }

    @Test
    void carriesIndexRollupAndRetryOntoTheSummary() {
        Knowledge kn = storedKnowledge("kn_1");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        entities.seed(entity("ent_a", kn.id(), EntityStatus.INGESTED, now));
        entities.seedIndexed("ent_a", 7, "bge-base-en-v1.5", now);

        EntitySummary summary = service.listEntities(kn.id(), null, 50, 0).items().get(0);

        assertEquals(7, summary.index().chunkCount());
        assertEquals("bge-base-en-v1.5", summary.index().embeddingModel());
        assertEquals(0, summary.retryCount());
        assertFalse(summary.needsReindex());
        assertNull(summary.index().error());
    }

    @Test
    void listEntitiesOnUnknownKnowledgeThrows() {
        assertThrows(NoSuchElementException.class, () -> service.listEntities("kn_missing", null, 50, 0));
    }

    // ---- cursor listing ----------------------------------------------------------------------

    @Test
    void listsCursorsForThatKnowledgeOnlyOrderedByIterableThenDirection() {
        Knowledge mine = storedKnowledge("kn_1");
        Knowledge other = storedKnowledge("kn_2");
        cursors.insertIfAbsent(TestData.cursor(mine.id(), "b_iter", CursorDirection.FORWARD, SourceType.SLACK));
        cursors.insertIfAbsent(TestData.cursor(mine.id(), "a_iter", CursorDirection.FORWARD, SourceType.SLACK));
        cursors.insertIfAbsent(TestData.cursor(mine.id(), "a_iter", CursorDirection.BACKWARD, SourceType.SLACK));
        cursors.insertIfAbsent(TestData.cursor(other.id(), "a_iter", CursorDirection.FORWARD, SourceType.SLACK));

        List<Cursor> listed = service.listCursors(mine.id());

        assertEquals(3, listed.size(), "another knowledge's cursors are excluded");
        assertEquals(List.of("a_iter", "a_iter", "b_iter"),
                listed.stream().map(Cursor::iterableId).toList());
        assertEquals(CursorDirection.BACKWARD, listed.get(0).direction(),
                "within an iterable, BACKWARD sorts before FORWARD (declaration order)");
    }

    @Test
    void cursorListingCarriesRetryAndStats() {
        Knowledge kn = storedKnowledge("kn_1");
        Cursor base = TestData.cursor(kn.id(), "a_iter", CursorDirection.BACKWARD, SourceType.SLACK);
        Instant ranAt = Instant.parse("2026-01-01T00:00:00Z");
        cursors.insertIfAbsent(new Cursor(base.id(), base.knowledgeId(), base.iterableId(),
                base.attributes(), base.direction(), base.position(), base.status(), base.lease(),
                new Cursor.Retry(2, "boom"), new Cursor.Stats(ranAt, 17), base.scope()));

        Cursor listed = service.listCursors(kn.id()).get(0);

        assertEquals(2, listed.retry().count());
        assertEquals("boom", listed.retry().lastError());
        assertEquals(ranAt, listed.stats().lastRunAt());
        assertEquals(17, listed.stats().fetched());
    }

    @Test
    void listCursorsOnUnknownKnowledgeThrows() {
        assertThrows(NoSuchElementException.class, () -> service.listCursors("kn_missing"));
    }
}
