package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.domain.model.Task;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Overlaying a patch onto a stored task.
 *
 * <p>The trap this guards is quiet: bound to a record, every key the caller did not send arrived as a
 * null and went through {@link Task}'s normalizing constructor, which turns nulls into defaults. A
 * merge that then asks "is this field null?" sees {@code SIMPLE}, {@code SUMMARY}, {@code "lite"} and
 * {@code ""} — real-looking values — and writes them. Renaming a RAW task converted it to a SIMPLE one
 * and blanked its description, with a 200.
 */
class TaskDtoPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Task existing() {
        return new Task("task_1", "Score roles", "Rates postings", Task.Mode.SIMPLE,
                "Score each posting.", Task.Output.PER_ITEM,
                List.of(new Task.Field("fit", Task.FieldType.NUMBER, "0-10", false)),
                null, null, "heavy", Task.SourceText.ENTITY, 4000, 8, null, null);
    }

    private static Task patched(String json) {
        try {
            JsonNode body = MAPPER.readTree(json);
            return TaskDto.patchOnto(existing(), body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("bad test fixture", e);
        }
    }

    @Test
    void anAbsentKeyLeavesTheFieldAlone() {
        Task edited = patched("{\"name\": \"Renamed\"}");

        Assertions.assertEquals("Renamed", edited.name());
        Assertions.assertEquals("Rates postings", edited.description());
        Assertions.assertEquals(Task.Mode.SIMPLE, edited.mode());
        Assertions.assertEquals(Task.Output.PER_ITEM, edited.output());
        Assertions.assertEquals("heavy", edited.llmProfile());
        Assertions.assertEquals(Task.SourceText.ENTITY, edited.sourceText());
        Assertions.assertEquals(4000, edited.contextChars());
        Assertions.assertEquals(1, edited.fields().size());
    }

    @Test
    void valuesAreSet() {
        Task edited = patched("""
                {"mode": "RAW", "system": "You are terse.", "user": "{{sources}}",
                 "llmProfile": "lite", "output": "SUMMARY", "fields": []}
                """);

        Assertions.assertEquals(Task.Mode.RAW, edited.mode());
        Assertions.assertEquals("You are terse.", edited.system());
        Assertions.assertEquals("lite", edited.llmProfile());
        Assertions.assertEquals(Task.Output.SUMMARY, edited.output());
        Assertions.assertTrue(edited.fields().isEmpty(),
                "an explicit empty array clears the fields — the console sends one when a task stops "
                        + "asking for a note per result");
    }

    @Test
    void idAndCreatedAtAreNotPatchable() {
        Task edited = patched("{\"id\": \"task_other\", \"name\": \"Renamed\"}");

        Assertions.assertEquals("task_1", edited.id());
    }

    @Test
    void aBlankNameKeepsTheStoredOne() {
        // The library lists by name; an empty one is an unfindable row.
        Assertions.assertEquals("Score roles", patched("{\"name\": \"\"}").name());
        Assertions.assertEquals("Score roles", patched("{\"name\": null}").name());
    }

    @Test
    void aClearedBudgetKeepsTheStoredOne() {
        // Zero is not "no limit" here — it would drop every source from the prompt.
        Assertions.assertEquals(4000, patched("{\"contextChars\": null}").contextChars());
        Assertions.assertEquals(8, patched("{\"maxSources\": 0}").maxSources());
    }

    @Test
    void anUnknownEnumOrWrongTypeIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> patched("{\"mode\": \"MAGIC\"}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> patched("{\"fields\": 3}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> patched("{\"contextChars\": \"lots\"}"));
    }
}
