package io.personalassistant.agent;

import io.personalassistant.agent.llm.LlmProfiles;
import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DefaultSearchAgent implements SearchAgent {

    static final String NO_SOURCES = "No matching sources were found, so there is nothing to answer from.";

    private final LlmProvider llm;
    private final AnswerPromptBuilder prompts;
    private final LlmProfiles profiles;
    private final TaskLibrary library;
    private final SourceTexts sourceTexts;

    @ConfigProperty(name = "app.agent.task", defaultValue = "answer")
    String taskId;

    @Inject
    public DefaultSearchAgent(LlmProvider llm, AnswerPromptBuilder prompts, LlmProfiles profiles,
                              TaskLibrary library, SourceTexts sourceTexts) {
        this.llm = llm;
        this.prompts = prompts;
        this.profiles = profiles;
        this.library = library;
        this.sourceTexts = sourceTexts;
    }

    @Override
    public String answer(SearchQuery query, List<SearchHit> hits) {
        return runTask(taskId, query, hits).reply();
    }

    @Override
    public TaskResult runTask(String id, SearchQuery query, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new TaskResult(NO_SOURCES, List.of());
        }
        TaskLibrary.ResolvedTask resolvedTask = library.resolve(id);
        TaskSpec task = resolvedTask.spec();
        SourceTexts.Resolved resolved = sourceTexts.resolve(task, hits);

        AnswerPromptBuilder.Rendered prompt = prompts.render(resolvedTask.prompt(), task, query,
                resolved.hits(), resolved.textByChunkId(), resolvedTask.variables());

        var messages = List.of(new LlmProvider.Message("user", prompt.user()));
        String reply = llm.complete(profiles.get(task.llmProfile()), task.responseFormat(),
                prompt.system(), messages);
        return new TaskResult(reply, resolved.hits());
    }
}
