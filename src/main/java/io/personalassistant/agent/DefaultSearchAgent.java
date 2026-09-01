package io.personalassistant.agent;

import io.personalassistant.agent.llm.LlmProfiles;
import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.agent.prompt.PromptCatalog;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default agent: builds a grounded prompt from the retrieved hits and asks the LLM to
 * answer with citations. Functional once an {@link LlmProvider} is wired; until then the
 * underlying provider throws.
 */
@ApplicationScoped
public class DefaultSearchAgent implements SearchAgent {

    /** Returned instead of calling the LLM when retrieval found nothing to ground an answer in. */
    static final String NO_SOURCES = "No matching sources were found, so there is nothing to answer from.";

    private final LlmProvider llm;
    private final AnswerPromptBuilder prompts;
    private final LlmProfiles profiles;
    private final PromptCatalog catalog;
    private final SourceTexts sourceTexts;

    /**
     * Which task in {@code config/prompts.json} answering runs. The task carries its own prompt, model
     * profile and budgets, so a variant (list-mode, summarisation) is a config entry plus this one key —
     * not a second code path. Replaces the former {@code app.agent.profile}, which named a model
     * directly and so could not carry anything else.
     */
    @ConfigProperty(name = "app.agent.task", defaultValue = "answer")
    String taskId;

    @Inject
    public DefaultSearchAgent(LlmProvider llm, AnswerPromptBuilder prompts, LlmProfiles profiles,
                              PromptCatalog catalog, SourceTexts sourceTexts) {
        this.llm = llm;
        this.prompts = prompts;
        this.profiles = profiles;
        this.catalog = catalog;
        this.sourceTexts = sourceTexts;
    }

    @Override
    public String answer(SearchQuery query, List<SearchHit> hits) {
        return runTask(taskId, query, hits);
    }

    @Override
    public String runTask(String id, SearchQuery query, List<SearchHit> hits) {
        return runTask(id, query, hits, java.util.Map.of());
    }

    @Override
    public String runTask(String id, SearchQuery query, List<SearchHit> hits,
                          java.util.Map<String, String> variables) {
        if (hits == null || hits.isEmpty()) {
            return NO_SOURCES;
        }
        TaskSpec task = catalog.task(id);
        SourceTexts.Resolved resolved = sourceTexts.resolve(task, hits);
        var messages = List.of(new LlmProvider.Message("user",
                prompts.user(task, query, resolved.hits(), resolved.textByChunkId())));
        return llm.complete(profiles.get(task.llmProfile()), task.responseFormat(),
                prompts.system(task, variables), messages);
    }
}
