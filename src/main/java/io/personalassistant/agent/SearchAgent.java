package io.personalassistant.agent;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;

/**
 * Optional agentic layer over retrieval. Given a query and the retrieved hits, produces a
 * grounded, cited answer — and may iterate (refine the query, fetch more) before
 * answering. Implemented with {@link io.personalassistant.agent.llm.LlmProvider}; later
 * could use a framework like LangChain4j. Returns plain text grounded in the hits.
 */
public interface SearchAgent {

    /**
     * @param query the user request
     * @param hits  retrieved, reranked grounding set
     * @return a synthesized answer that cites the supplied hits
     */
    String answer(SearchQuery query, List<SearchHit> hits);
}
