package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.service.KnowledgePatch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KnowledgePatchDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KnowledgePatch patch(String json) {
        try {
            JsonNode body = MAPPER.readTree(json);
            return new KnowledgePatchDto(body).toPatch();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("bad test fixture", e);
        }
    }

    @Test
    void clearingTheCronIsWhatMovesASourceBackOntoAnInterval() {
        KnowledgePatch patch =
                patch("{\"cron\": null, \"interval\": \"1h\", \"scheduleEnabled\": true}");

        Assertions.assertTrue(patch.schedule().cron().present(), "the key was sent");
        Assertions.assertNull(patch.schedule().cron().value(), "and it clears");
        Assertions.assertEquals("1h", patch.schedule().interval().value());
    }

    @Test
    void anAbsentKeyIsNotAClear() {
        KnowledgePatch patch = patch("{\"name\": \"Renamed\"}");

        Assertions.assertEquals("Renamed", patch.name().value());
        Assertions.assertFalse(patch.schedule().cron().present());
        Assertions.assertFalse(patch.chunking().maxSize().present());
        Assertions.assertTrue(patch.chunking().isEmpty());
    }

    @Test
    void blankingAChunkingOverrideHandsItBackToTheServerDefault() {
        KnowledgePatch patch = patch(
                "{\"chunkingStrategy\": null, \"chunkingMaxSize\": null, \"chunkingOverlap\": null}");

        Assertions.assertFalse(patch.chunking().isEmpty(), "this is an edit, not silence");
        Assertions.assertTrue(patch.chunking().strategy().present());
        Assertions.assertNull(patch.chunking().strategy().value());
        Assertions.assertNull(patch.chunking().maxSize().value());
    }

    @Test
    void retentionClearsBackToNeverExpire() {
        KnowledgePatch patch = patch("{\"retentionPeriod\": null}");

        Assertions.assertTrue(patch.retentionPeriod().present());
        Assertions.assertNull(patch.retentionPeriod().value());
    }

    @Test
    void valuesAreSet() {
        KnowledgePatch patch = patch("""
                {"name": "Docs", "inputs": {"rootPath": "/tmp"}, "backfillEnabled": true,
                 "chunkingStrategy": "recursive", "chunkingMaxSize": 800,
                 "chunkingSeparators": ["\\n\\n", "\\n"]}
                """);

        Assertions.assertEquals("Docs", patch.name().value());
        Assertions.assertEquals(Map.of("rootPath", "/tmp"), patch.inputs().value());
        Assertions.assertEquals(Boolean.TRUE, patch.backfillEnabled().value());
        Assertions.assertEquals(800, patch.chunking().maxSize().value());
        Assertions.assertEquals(List.of("\n\n", "\n"), patch.chunking().separators().value());
    }

    @Test
    void fieldsWithNoEmptyStateRejectAnExplicitNull() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"name\": null}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"inputs\": null}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"auth\": null}"));
    }

    @Test
    void aWrongTypedFieldIsRejectedRatherThanDropped() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patch("{\"chunkingMaxSize\": \"big\"}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patch("{\"scheduleEnabled\": \"yes\"}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"inputs\": []}"));
    }

    @Test
    void anUnknownConnectorTypeIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> patch("{\"type\": \"FAXMACHINE\"}"));
    }
}
