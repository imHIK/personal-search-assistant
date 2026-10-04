package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.service.DigestPatch;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DigestPatchDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DigestPatch patch(String json) {
        try {
            JsonNode body = MAPPER.readTree(json);
            return new DigestPatchDto(body).toPatch();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("bad test fixture", e);
        }
    }

    /** Every field set, so "unchanged" is distinguishable from "cleared". */
    private static Digest existing() {
        return new Digest("dig_1", "Roles", "engineer", List.of("kn_1"),
                Map.of("metadata.remote", true), "7d", SyncSchedule.ofInterval(Duration.ofDays(1)),
                "job-fit", 5, true, 1, true, true, null, null, null, null);
    }

    @Test
    void anAbsentKeyLeavesTheFieldAlone() {
        Digest edited = patch("{\"name\": \"Renamed\"}").applyTo(existing());

        Assertions.assertEquals("Renamed", edited.name());
        Assertions.assertEquals("7d", edited.window());
        Assertions.assertEquals("job-fit", edited.taskId());
        Assertions.assertEquals(1, edited.maxChunksPerEntity());
    }

    @Test
    void anExplicitNullClearsTheField() {
        Digest edited = patch("{\"window\": null, \"taskId\": null, \"maxChunksPerEntity\": null}")
                .applyTo(existing());

        Assertions.assertNull(edited.window());
        Assertions.assertNull(edited.taskId());
        Assertions.assertNull(edited.maxChunksPerEntity());
        Assertions.assertEquals("engineer", edited.query(), "a key nobody sent is still untouched");
    }

    @Test
    void useLlmIsKeptWhenAbsentSetWhenSentAndDefaultsOnWhenCleared() {
        Assertions.assertTrue(patch("{\"name\": \"Renamed\"}").applyTo(existing()).useLlm());
        Digest off = patch("{\"useLlm\": false}").applyTo(existing());
        Assertions.assertFalse(off.useLlm());
        Assertions.assertTrue(patch("{\"useLlm\": null}").applyTo(off).useLlm());
    }

    @Test
    void anEmptyBodyChangesNothing() {
        Digest before = existing();
        Digest edited = patch("{}").applyTo(before);

        Assertions.assertEquals(before, edited);
    }

    @Test
    void valuesAreSet() {
        Digest edited = patch("""
                {"name": "New", "query": "backend", "window": "30d", "topK": 20,
                 "knowledgeIds": ["kn_a", "kn_b"], "filters": {"metadata.remote": true},
                 "onlyNew": false, "collapseDuplicates": false, "interval": "6h"}
                """).applyTo(existing());

        Assertions.assertEquals("New", edited.name());
        Assertions.assertEquals("30d", edited.window());
        Assertions.assertEquals(20, edited.topK());
        Assertions.assertEquals(List.of("kn_a", "kn_b"), edited.knowledgeIds());
        Assertions.assertEquals(Map.of("metadata.remote", true), edited.filters());
        Assertions.assertFalse(edited.onlyNew());
        Assertions.assertFalse(edited.collapseDuplicates());
        Assertions.assertEquals(Duration.ofHours(6), edited.schedule().interval());
    }

    @Test
    void clearingAFieldWithNoOffFallsBackToItsDefault() {
        Digest edited = patch("{\"topK\": null, \"onlyNew\": null, \"enabled\": null}")
                .applyTo(existing());

        Assertions.assertEquals(Digest.DEFAULT_TOP_K, edited.topK());
        Assertions.assertTrue(edited.onlyNew());
        Assertions.assertTrue(edited.enabled());
    }

    @Test
    void aWrongTypedFieldIsRejectedRatherThanDropped() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"topK\": \"ten\"}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"name\": 7}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"onlyNew\": \"yes\"}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patch("{\"knowledgeIds\": \"kn_1\"}"));
    }

    @Test
    void anUnparseableWindowOrIntervalIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patch("{\"window\": \"1 fortnight\"}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patch("{\"interval\": \"sometimes\"}"));
    }

    @Test
    void cronWinsOverInterval() {
        Digest edited = patch("{\"cron\": \"0 7 * * *\", \"interval\": \"1d\"}").applyTo(existing());

        Assertions.assertEquals("0 7 * * *", edited.schedule().cron());
    }
}
