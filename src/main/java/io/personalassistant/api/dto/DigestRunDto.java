package io.personalassistant.api.dto;

import io.personalassistant.domain.model.DigestRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record DigestRunDto(
        String id,
        String digestId,
        Instant ranAt,
        List<Item> items,
        String taskOutput,
        int candidates,
        int suppressed,
        int outsideWindow,
        String error,
        String taskError) {

    public record Item(String entityId, String chunkId, String title, String uri, double score,
                       String snippet, java.util.Map<String, Object> annotations) {}

    public static DigestRunDto from(DigestRun run) {
        List<Item> items = new ArrayList<>(run.items().size());
        for (DigestRun.Item item : run.items()) {
            items.add(new Item(item.entityId(), item.chunkId(), item.title(), item.uri(),
                    item.score(), item.snippet(), item.annotations()));
        }
        return new DigestRunDto(run.id(), run.digestId(), run.ranAt(), items, run.taskOutput(),
                run.candidates(), run.suppressed(), run.outsideWindow(), run.error(), run.taskError());
    }
}
