package io.personalassistant.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskMetadataTest {

    private static Task task(Task.Mode mode, Task.Output output, List<Task.Field> fields) {
        return new Task("task_1", "Facts", "", mode, "Extract.", output, fields,
                mode == Task.Mode.RAW ? "sys" : null, mode == Task.Mode.RAW ? "{{sources}}" : null,
                "lite", Task.SourceText.ENTITY, 0, 0, null, null);
    }

    @Test
    void theContractIsOneObjectWithoutASourceIndex() {
        String contract = task(Task.Mode.SIMPLE, Task.Output.METADATA, List.of(
                new Task.Field("yoe", Task.FieldType.NUMBER, "Years required", true),
                new Task.Field("skills", Task.FieldType.LIST, "", false, List.of("Java", "Go"))))
                .outputContract();

        assertTrue(contract.contains("{\"yoe\": 0, \"skills\": [\"...\"]}"), contract);
        assertFalse(contract.contains("\"source\""), contract);
        assertTrue(contract.contains("each one of \"Java\", \"Go\""), contract);
        assertTrue(contract.contains("or null if there is none. Years required"), contract);
    }

    @Test
    void metadataUsesItsOwnWrapper() {
        Task t = task(Task.Mode.SIMPLE, Task.Output.METADATA,
                List.of(new Task.Field("yoe", Task.FieldType.NUMBER, "", true)));

        assertTrue(t.metadata());
        assertFalse(t.perItem());
        assertEquals(Task.METADATA_PROMPT, t.promptId());
        assertTrue(t.problems().isEmpty(), t.problems().toString());
    }

    @Test
    void rejectsMetadataShapesOutsideMetadataTasks() {
        assertFalse(task(Task.Mode.SIMPLE, Task.Output.METADATA, List.of()).problems().isEmpty(),
                "needs a field");
        assertFalse(task(Task.Mode.RAW, Task.Output.METADATA, List.of()).problems().isEmpty(),
                "must be SIMPLE");
        assertFalse(task(Task.Mode.SIMPLE, Task.Output.PER_ITEM,
                List.of(new Task.Field("ok", Task.FieldType.BOOLEAN, "", false))).problems().isEmpty(),
                "BOOLEAN is metadata only");
        assertFalse(task(Task.Mode.SIMPLE, Task.Output.METADATA,
                List.of(new Task.Field("n", Task.FieldType.NUMBER, "", false, List.of("1")))).problems()
                .isEmpty(), "allowed values are TEXT and LIST only");
    }
}
