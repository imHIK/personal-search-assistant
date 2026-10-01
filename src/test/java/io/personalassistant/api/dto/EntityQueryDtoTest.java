package io.personalassistant.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.EntityFilter;
import io.personalassistant.domain.model.enums.EntityType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityQueryDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EntityFilter parse(String json) throws Exception {
        return MAPPER.readValue(json, EntityQueryDto.class).toFilter();
    }

    @Test
    void readsScalarsArraysAndOperatorObjects() throws Exception {
        EntityFilter f = parse("""
                {"entityTypes": ["job_posting"],
                 "filters": {
                   "metadata.remote": true,
                   "metadata.company": ["Acme", "Beta"],
                   "enriched.yoe": {"gte": 2, "lte": 5},
                   "metadata.postedAt": {"gte": "2026-09-01"},
                   "custom.hidden": {"ne": true}
                 },
                 "sort": {"path": "metadata.postedAt"}}""");

        assertEquals(Set.of(EntityType.JOB_POSTING), f.entityTypes());
        assertEquals(List.of(
                new EntityFilter.Condition("metadata.remote", EntityFilter.Op.EQ, true),
                new EntityFilter.Condition("metadata.company", EntityFilter.Op.IN, List.of("Acme", "Beta")),
                new EntityFilter.Condition("enriched.yoe", EntityFilter.Op.GTE, 2L),
                new EntityFilter.Condition("enriched.yoe", EntityFilter.Op.LTE, 5L),
                new EntityFilter.Condition("metadata.postedAt", EntityFilter.Op.GTE,
                        Instant.parse("2026-09-01T00:00:00Z")),
                new EntityFilter.Condition("custom.hidden", EntityFilter.Op.NE, true)), f.conditions());
        assertEquals(new EntityFilter.Sort("metadata.postedAt", true), f.sort(), "descending by default");
    }

    @Test
    void defaultsToNewestFirst() throws Exception {
        assertEquals(EntityFilter.Sort.NEWEST_FIRST, parse("{}").sort());
    }

    @Test
    void rejectsUnknownOperatorsAndPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("{\"filters\": {\"metadata.company\": {\"like\": \"A\"}}}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"filters\": {\"auth.token\": \"x\"}}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"entityTypes\": [\"NOPE\"]}"));
    }
}
