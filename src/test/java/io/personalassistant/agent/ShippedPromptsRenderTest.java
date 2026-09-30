package io.personalassistant.agent;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.PromptTemplate;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The one place an unrenderable shipped task is caught: catalogue validation cannot see what callers supply,
 * and other tests stub the agent.
 */
class ShippedPromptsRenderTest {

    private final PromptCatalog catalog = PromptCatalog.bundled();

    private AnswerPromptBuilder builder() {
        return new AnswerPromptBuilder(catalog, FieldSets.bundled());
    }

    private static SearchHit hit() {
        return new SearchHit("c0", "ent_1", "kn_1", 0, "A source", "some source text", "snippet",
                "uri://a", 1.0, Map.of());
    }

    @Test
    void everyShippedTaskRendersWithTheFrameworkValuesAlone() {
        AnswerPromptBuilder builder = builder();
        SearchQuery query = SearchQuery.of("anything");

        for (String taskId : catalog.taskIds()) {
            TaskSpec task = catalog.task(taskId);
            String system = Assertions.assertDoesNotThrow(() -> builder.system(task, Map.of()),
                    "system prompt for \"" + taskId + "\" did not render");
            String user = Assertions.assertDoesNotThrow(
                    () -> builder.user(task, query, List.of(hit()), Map.of()),
                    "user prompt for \"" + taskId + "\" did not render");

            Assertions.assertFalse(system.contains("{{"),
                    "unresolved placeholder left in \"" + taskId + "\" system prompt: " + system);
            Assertions.assertFalse(user.contains("{{"),
                    "unresolved placeholder left in \"" + taskId + "\" user prompt: " + user);
        }
    }

    @Test
    void aPromptNeedingAnUnsuppliedVariableFails() {
        PromptTemplate summary = catalog.prompt("user-task-summary");

        Assertions.assertThrows(IllegalStateException.class,
                () -> summary.renderSystem(Map.of("fence", "~~~")));
    }
}
