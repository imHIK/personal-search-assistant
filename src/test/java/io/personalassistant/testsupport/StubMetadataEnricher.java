package io.personalassistant.testsupport;

import io.personalassistant.agent.MetadataEnricher;
import io.personalassistant.domain.model.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StubMetadataEnricher implements MetadataEnricher {

    public Map<String, Object> reply = Map.of();
    public RuntimeException failure;
    public final List<String> calls = new ArrayList<>();

    @Override
    public Map<String, Object> enrich(Task task, String title, String text) {
        calls.add(task.id() + ":" + title);
        if (failure != null) {
            throw failure;
        }
        return reply;
    }
}
