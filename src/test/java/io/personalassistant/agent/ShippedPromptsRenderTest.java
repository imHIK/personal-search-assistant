package io.personalassistant.agent;

import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.common.fields.FieldSets;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Renders every task in the <em>shipped</em> catalogue through the real prompt builder.
 *
 * <p>This exists because of a bug that reached a running system: the {@code document-facets} prompt
 * declared {@code {{maxFacets}}}, the catalogue's own validation was satisfied — the variable <em>was</em>
 * declared — but nothing supplied it at render time, so every call threw and silently took the fallback
 * path. Every other test stubbed the agent, so prompt rendering was never exercised at all.
 *
 * <p>The guard is {@link #CALLER_VARIABLES}: a new task whose prompt needs a value must either be
 * supplied by the framework or listed here, and whoever adds it has to point at the code that passes it.
 * A task added without that fails this test rather than degrading in production.
 */
class ShippedPromptsRenderTest {

    /** What each task's caller supplies beyond the framework's own values. */
    private static final Map<String, Map<String, String>> CALLER_VARIABLES = Map.of(
            "answer", Map.of(),
            // DocumentQueryPlanner.derive
            "document-facets", Map.of("maxFacets", "8"),
            "job-fit", Map.of());

    private final PromptCatalog catalog = PromptCatalog.bundled();

    private AnswerPromptBuilder builder() {
        return new AnswerPromptBuilder(catalog, FieldSets.bundled());
    }

    private static SearchHit hit() {
        return new SearchHit("c0", "ent_1", "kn_1", 0, "A source", "some source text", "snippet",
                "uri://a", 1.0, Map.of());
    }

    @Test
    void everyShippedTaskRendersWithTheVariablesItsCallerSupplies() {
        AnswerPromptBuilder builder = builder();
        SearchQuery query = SearchQuery.of("anything");

        for (String taskId : catalog.taskIds()) {
            Map<String, String> supplied = CALLER_VARIABLES.get(taskId);
            Assertions.assertNotNull(supplied,
                    "task \"" + taskId + "\" is shipped but this test does not say what supplies its"
                            + " prompt variables — add it to CALLER_VARIABLES, naming the caller");

            TaskSpec task = catalog.task(taskId);
            String system = Assertions.assertDoesNotThrow(() -> builder.system(task, supplied),
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
        // Pins the failure mode itself: rendering document-facets without maxFacets must throw rather
        // than pass the placeholder through to the model.
        AnswerPromptBuilder builder = builder();
        TaskSpec facets = catalog.task("document-facets");

        Assertions.assertThrows(IllegalStateException.class, () -> builder.system(facets, Map.of()));
    }
}
