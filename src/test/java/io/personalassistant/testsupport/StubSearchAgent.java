package io.personalassistant.testsupport;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Scriptable {@link SearchAgent}. Exists because the interface gained {@code runTask} and so is no
 * longer a functional interface — a lambda can no longer stand in for it.
 */
public class StubSearchAgent implements SearchAgent {

    private final BiFunction<SearchQuery, List<SearchHit>, String> reply;

    /** Test observability: the task ids {@link #runTask} was asked for, in order. */
    public final List<String> taskIds = new ArrayList<>();

    public StubSearchAgent(String fixedReply) {
        this((query, hits) -> fixedReply);
    }

    public StubSearchAgent(BiFunction<SearchQuery, List<SearchHit>, String> reply) {
        this.reply = reply;
    }

    /** An agent whose every call throws — for the answer-failure paths. */
    public static StubSearchAgent throwing(RuntimeException failure) {
        return new StubSearchAgent((query, hits) -> {
            throw failure;
        });
    }

    @Override
    public String answer(SearchQuery query, List<SearchHit> hits) {
        return reply.apply(query, hits);
    }

    /** Test observability: the variable maps {@link #runTask} was given, in order. */
    public final List<java.util.Map<String, String>> variables = new ArrayList<>();

    @Override
    public String runTask(String taskId, SearchQuery query, List<SearchHit> hits) {
        return runTask(taskId, query, hits, java.util.Map.of());
    }

    @Override
    public String runTask(String taskId, SearchQuery query, List<SearchHit> hits,
                          java.util.Map<String, String> values) {
        return runTaskWithSources(taskId, query, hits, values).reply();
    }

    /**
     * Reports the hits it was given as the sources, which is what a chunk-level task actually renders.
     * A test exercising the whole-entity collapse supplies hits already shaped that way rather than
     * having this stub reimplement {@code SourceTexts}.
     */
    @Override
    public TaskResult runTaskWithSources(String taskId, SearchQuery query, List<SearchHit> hits,
                                         java.util.Map<String, String> values) {
        taskIds.add(taskId);
        variables.add(values);
        return new TaskResult(reply.apply(query, hits), hits == null ? List.of() : List.copyOf(hits));
    }
}
