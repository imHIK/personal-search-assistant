package io.personalassistant.api.dto;

import io.personalassistant.domain.model.DigestRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire shape for one execution of a digest.
 *
 * @param taskOutput the LLM task's reply, or null when the digest names no task
 * @param error      why the run failed, or null. A failed run is still returned — a digest that has
 *                   been erroring should be visible as such rather than merely empty
 */
public record DigestRunDto(
        String id,
        String digestId,
        Instant ranAt,
        List<Item> items,
        String taskOutput,
        String error) {

    public record Item(String entityId, String chunkId, String title, String uri, double score,
                       String snippet) {}

    public static DigestRunDto from(DigestRun run) {
        List<Item> items = new ArrayList<>(run.items().size());
        for (DigestRun.Item item : run.items()) {
            items.add(new Item(item.entityId(), item.chunkId(), item.title(), item.uri(),
                    item.score(), item.snippet()));
        }
        return new DigestRunDto(run.id(), run.digestId(), run.ranAt(), items, run.taskOutput(),
                run.error());
    }
}
