package io.personalassistant.agent;

import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Default agent: builds a grounded prompt from the retrieved hits and asks the LLM to
 * answer with citations. Functional once an {@link LlmProvider} is wired; until then the
 * underlying provider throws.
 */
@ApplicationScoped
public class DefaultSearchAgent implements SearchAgent {

    private final LlmProvider llm;

    @Inject
    public DefaultSearchAgent(LlmProvider llm) {
        this.llm = llm;
    }

    @Override
    public String answer(SearchQuery query, List<SearchHit> hits) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            context.append("[").append(i + 1).append("] ")
                    .append(h.title()).append("\n")
                    .append(h.snippet()).append("\n\n");
        }
        String system = "You answer strictly from the provided sources and cite them as [n]. "
                + "If the sources do not contain the answer, say so.";
        var messages = List.of(new LlmProvider.Message("user",
                "Question: " + query.text() + "\n\nSources:\n" + context));
        return llm.complete(system, messages);
    }
}
