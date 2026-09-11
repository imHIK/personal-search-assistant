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

/**
 * The three states a PATCH body can express, at the layer that gets them wrong.
 *
 * <p>This is the seam the console's "turn it off" controls broke on, and no service-level test could
 * have caught it: by the time a {@link DigestPatch} exists the distinction has already been made or
 * lost. Both wrong answers are cheap to reintroduce — binding to plain fields collapses "cleared" into
 * "unchanged", binding to {@code Optional} collapses "unchanged" into "cleared" — so both are asserted
 * here rather than only the happy path.
 */
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

    /** A fully-populated digest, so "unchanged" is visibly different from "cleared". */
    private static Digest existing() {
        return new Digest("dig_1", "Roles", "engineer", "ent_cv", List.of("kn_1"),
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
        Assertions.assertEquals("ent_cv", edited.sourceEntityId());
    }

    @Test
    void anExplicitNullClearsTheField() {
        // "Look back: no time limit", "Then: nothing", "One result per document: off" — every one of
        // these sends a null, and every one of them used to be answered with 200 and no change.
        Digest edited = patch("{\"window\": null, \"taskId\": null, \"maxChunksPerEntity\": null}")
                .applyTo(existing());

        Assertions.assertNull(edited.window());
        Assertions.assertNull(edited.taskId());
        Assertions.assertNull(edited.maxChunksPerEntity());
        Assertions.assertEquals("engineer", edited.query(), "a key nobody sent is still untouched");
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
        // These are primitives on the digest; there is no unset to write, and an NPE on unboxing is
        // not an answer to "the user cleared the box".
        Digest edited = patch("{\"topK\": null, \"onlyNew\": null, \"enabled\": null}")
                .applyTo(existing());

        Assertions.assertEquals(Digest.DEFAULT_TOP_K, edited.topK());
        Assertions.assertTrue(edited.onlyNew());
        Assertions.assertTrue(edited.enabled());
    }

    @Test
    void aWrongTypedFieldIsRejectedRatherThanDropped() {
        // Dropping it would be the same silent no-op this whole class exists to prevent.
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
