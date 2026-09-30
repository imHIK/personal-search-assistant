package io.personalassistant.agent;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;

/** LLM output grounded in retrieved hits: the search answer, or any task from the task library. */
public interface SearchAgent {

    /**
     * @param reply   the model's reply, verbatim; a JSON task's reply is JSON text for the caller to parse
     * @param sources the hits the prompt actually carried, index {@code n-1} being source {@code [n]}. Not
     *                always the hits passed in: whole-entity tasks collapse them to one per document
     */
    record TaskResult(String reply, List<SearchHit> sources) {}

    String answer(SearchQuery query, List<SearchHit> hits);

    /** @throws java.util.NoSuchElementException if no such task exists in the library */
    TaskResult runTask(String taskId, SearchQuery query, List<SearchHit> hits);
}
