package io.personalassistant.testsupport;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class StubSearchAgent implements SearchAgent {

    private final BiFunction<SearchQuery, List<SearchHit>, String> reply;

    public final List<String> taskIds = new ArrayList<>();

    public StubSearchAgent(String fixedReply) {
        this((query, hits) -> fixedReply);
    }

    public StubSearchAgent(BiFunction<SearchQuery, List<SearchHit>, String> reply) {
        this.reply = reply;
    }

    public static StubSearchAgent throwing(RuntimeException failure) {
        return new StubSearchAgent((query, hits) -> {
            throw failure;
        });
    }

    @Override
    public String answer(SearchQuery query, List<SearchHit> hits) {
        return reply.apply(query, hits);
    }

    /**
     * Reports the given hits as the sources; it does not collapse them to whole entities the way SourceTexts
     * does.
     */
    @Override
    public TaskResult runTask(String taskId, SearchQuery query, List<SearchHit> hits) {
        taskIds.add(taskId);
        return new TaskResult(reply.apply(query, hits), hits == null ? List.of() : List.copyOf(hits));
    }
}
