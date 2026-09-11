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
     * A task's reply together with the hits actually rendered into its sources block, in the order they
     * were numbered.
     *
     * <p>The order is the contract: sources are numbered 1-based and positionally, so a reply that
     * describes its sources by number can only be matched back to real results by a caller holding this
     * exact list. {@link #runTask} cannot supply it — whole-entity tasks collapse the hits to one per
     * document before rendering, so the list the model saw is not the list the caller passed in.
     *
     * @param reply   the model's reply, verbatim
     * @param sources the hits the prompt actually carried, index {@code n-1} being source {@code [n]}
     */
    record TaskResult(String reply, List<SearchHit> sources) {}

    /**
     * @param query the user request
     * @param hits  retrieved, reranked grounding set
     * @return a synthesized answer that cites the supplied hits
     */
    String answer(SearchQuery query, List<SearchHit> hits);

    /**
     * Run any task from the prompt catalogue over a grounding set.
     *
     * <p>{@link #answer} is this with the configured answering task, kept as a named method because it
     * is the one the read path calls. Everything a task varies — its prompt, model profile, context
     * budget, whether sources are chunks or whole entities, whether the reply should be JSON — lives in
     * {@code config/prompts.json}, so a second task is a config entry rather than a second code path.
     *
     * <p>The reply is returned verbatim. A task that asked for JSON returns JSON <em>text</em>; parsing
     * belongs to the caller, which knows the shape it expects and what to do when the model does not
     * produce it (see {@code JsonReplies}).
     *
     * @throws java.util.NoSuchElementException if no such task is filed in the catalogue
     */
    String runTask(String taskId, SearchQuery query, List<SearchHit> hits);

    /**
     * Same, supplying values for the prompt's own declared variables.
     *
     * <p>A prompt may need something only its caller knows — a cap, a threshold, a label. Those are
     * declared in {@code variables} so they are reviewable, and supplied here. {@code query} and
     * {@code sources} are always provided by the framework and need not appear.
     *
     * @throws IllegalStateException if the prompt uses a placeholder nothing supplies
     */
    String runTask(String taskId, SearchQuery query, List<SearchHit> hits,
                   java.util.Map<String, String> variables);

    /**
     * Same as {@link #runTask(String, SearchQuery, List)}, also returning the sources the prompt
     * carried so a per-source reply can be joined back onto the results.
     *
     * @throws java.util.NoSuchElementException if no such task exists in the library
     */
    TaskResult runTaskWithSources(String taskId, SearchQuery query, List<SearchHit> hits,
                                  java.util.Map<String, String> variables);
}
