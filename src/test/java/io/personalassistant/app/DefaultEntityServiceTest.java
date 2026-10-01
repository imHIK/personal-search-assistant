package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.EntityFilter.Condition;
import io.personalassistant.domain.model.EntityFilter.Op;
import io.personalassistant.domain.model.FacetValue;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.service.EntityService;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultEntityServiceTest {

    private final InMemoryEntityRepository entities = new InMemoryEntityRepository();
    private final DefaultEntityService service = new DefaultEntityService(entities);

    private void posting(String id, String kn, Instant created, Map<String, Object> metadata,
                         Map<String, Object> enriched) {
        Map<String, Object> meta = new HashMap<>(metadata);
        meta.put("title", id);
        entities.store.put(id, new Entity(id, kn, "acme", EntityType.JOB_POSTING, id, Map.of(),
                Entity.Content.ofText("body"), meta, "c", EntityStatus.INDEXED, false, false,
                Entity.IndexInfo.empty(), null, Entity.Retry.zero(), created, created, null, 0L, enriched, null,
                Map.of()));
    }

    @BeforeEach
    void seed() {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        posting("p1", "kn_a", t, Map.of("company", "Acme", "remote", true), Map.of("yoe", 2L,
                "skills", List.of("Java", "Go")));
        posting("p2", "kn_a", t.plusSeconds(60), Map.of("company", "Beta", "remote", false), Map.of("yoe", 6L,
                "skills", List.of("Go")));
        posting("p3", "kn_b", t.plusSeconds(120), Map.of("company", "Acme", "remote", false), Map.of());
        entities.store.put("m1", new Entity("m1", "kn_c", "inbox", EntityType.MESSAGE, "m1", Map.of(),
                Entity.Content.ofText("x"), Map.of("title", "mail"), "c", EntityStatus.INDEXED, false, false,
                Entity.IndexInfo.empty(), null, Entity.Retry.zero(), t, t, null, 0L));
    }

    private static EntityFilter jobs(Condition... conditions) {
        return new EntityFilter(Set.of(EntityType.JOB_POSTING), null, null, List.of(conditions), null);
    }

    private static List<String> ids(EntityService.Page page) {
        return page.items().stream().map(Entity::id).toList();
    }

    @Test
    void listsOneTypeAcrossKnowledgesNewestFirst() {
        EntityService.Page page = service.query(jobs(), 50, 0);

        assertEquals(List.of("p3", "p2", "p1"), ids(page));
        assertEquals(3, page.total());
    }

    @Test
    void filtersOnConnectorEnrichedAndListValuedFields() {
        assertEquals(List.of("p3", "p1"),
                ids(service.query(jobs(new Condition("metadata.company", Op.EQ, "Acme")), 50, 0)));
        assertEquals(List.of("p1"), ids(service.query(jobs(new Condition("enriched.yoe", Op.LTE, 5L)), 50, 0)),
                "an entity without the field does not satisfy an upper bound");
        assertEquals(List.of("p2", "p1"), ids(service.query(jobs(new Condition("enriched.skills", Op.IN,
                List.of("Go"))), 50, 0)), "any element of a list matches");
    }

    @Test
    void hiddenIsExcludedByNotEqualSoUnmarkedItemsStay() {
        service.mergeCustom("p2", Map.of("hidden", true));

        assertEquals(List.of("p3", "p1"), ids(service.query(jobs(new Condition("custom.hidden", Op.NE, true)),
                50, 0)));
    }

    @Test
    void customMarksMergeAndANullRemovesOne() {
        Map<String, Object> mark = new HashMap<>();
        mark.put("applied", "2026-09-02T10:00:00Z");
        service.mergeCustom("p1", mark);
        service.mergeCustom("p1", Map.of("hidden", true));
        mark.put("applied", null);
        Entity after = service.mergeCustom("p1", mark);

        assertEquals(Map.of("hidden", true), after.custom());
        assertThrows(NoSuchElementException.class, () -> service.mergeCustom("nope", Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.mergeCustom("p1", Map.of("bad key", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.mergeCustom("p1", Map.of("x", List.of(1))));
    }

    @Test
    void facetsCountListElementsAndIgnoreTheConditions() {
        EntityFilter filtered = jobs(new Condition("metadata.company", Op.EQ, "Beta"));

        Map<String, List<FacetValue>> facets = service.facets(filtered,
                List.of("metadata.company", "enriched.skills"), 10);

        assertEquals(new FacetValue("Acme", 2), facets.get("metadata.company").get(0),
                "counted over the whole scope: picking Beta must not hide Acme");
        assertEquals(new FacetValue("Go", 2), facets.get("enriched.skills").get(0));
    }

    @Test
    void onlyUserFacingPathsCanBeFilteredOrSorted() {
        assertThrows(IllegalArgumentException.class, () -> new Condition("raw.token", Op.EQ, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Condition("lease.owner", Op.EQ, "x"));
        assertThrows(IllegalArgumentException.class, () -> service.facets(jobs(), List.of("content.text"), 5));
        assertFalse(service.query(jobs(), 500, 0).limit() > DefaultEntityService.MAX_PAGE);
        assertTrue(service.query(jobs(), 0, 0).limit() >= 1);
    }
}
