package io.personalassistant.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.personalassistant.agent.llm.LlmProvider;
import org.junit.jupiter.api.Test;

class TaskSpecTest {

    @Test
    void keepsValidValues() {
        TaskSpec spec = new TaskSpec("answer", "answer", "answer", 24_000, 10);

        assertEquals("answer", spec.id());
        assertEquals("answer", spec.promptId());
        assertEquals("answer", spec.llmProfile());
        assertEquals(24_000, spec.contextChars());
        assertEquals(10, spec.maxSources());
    }

    @Test
    void aBlankPromptIdFallsBackToTheTaskId() {
        assertEquals("summarise", new TaskSpec("summarise", null, "lite", 1, 1).promptId());
        assertEquals("summarise", new TaskSpec("summarise", "  ", "lite", 1, 1).promptId());
    }

    @Test
    void aBlankProfileFallsBackToDefault() {
        assertEquals("default", new TaskSpec("t", "p", null, 1, 1).llmProfile());
        assertEquals("default", new TaskSpec("t", "p", "", 1, 1).llmProfile());
    }

    @Test
    void negativeBudgetsClampToZeroWhichMeansUnbounded() {
        TaskSpec spec = new TaskSpec("t", "p", "lite", -5, -1);

        assertEquals(0, spec.contextChars());
        assertEquals(0, spec.maxSources());
    }

    @Test
    void aBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TaskSpec(null, "p", "lite", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskSpec("  ", "p", "lite", 1, 1));
    }

    @Test
    void sourceTextAndResponseFormatDefaultToThePreExistingBehaviour() {
        TaskSpec spec = new TaskSpec("t", "t", "default", 100, 5, null, null);

        assertEquals(TaskSpec.SourceText.CHUNK, spec.sourceText());
        assertEquals(LlmProvider.ResponseFormat.TEXT, spec.responseFormat());
    }

    @Test
    void theFiveArgFormIsTheAnsweringShape() {
        TaskSpec spec = new TaskSpec("answer", "answer", "answer", 24_000, 10);

        assertEquals(TaskSpec.SourceText.CHUNK, spec.sourceText());
        assertEquals(LlmProvider.ResponseFormat.TEXT, spec.responseFormat());
    }
}
