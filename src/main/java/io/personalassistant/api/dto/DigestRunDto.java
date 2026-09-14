package io.personalassistant.api.dto;

import io.personalassistant.domain.model.DigestRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire shape for one execution of a digest.
 *
 * @param taskOutput the LLM task's reply, verbatim, or null when the digest names no task. Kept even
 *                   once read into each item's {@code annotations}, so a reply that would not parse can
 *                   still be looked at
 * @param candidates how many results the search returned before already-seen ones were dropped
 * @param suppressed how many of those were dropped as already reported. With {@code candidates} these
 *                   let the console tell "nothing matched" apart from "everything matched was already
 *                   seen" — two very different reasons for an empty digest
 * @param outsideWindow how many results the same search finds once the look-back window is removed,
 *                   counted only when the windowed search found nothing. The window filters on when a
 *                   chunk was <em>indexed</em>, so a source that was ingested once and left alone drops
 *                   out of a short window and stays out; this is what lets the console say "widen the
 *                   look-back" instead of "nothing matched"
 * @param error      why the run failed, or null. A failed run is still returned — a digest that has
 *                   been erroring should be visible as such rather than merely empty
 */
public record DigestRunDto(
        String id,
        String digestId,
        Instant ranAt,
        List<Item> items,
        String taskOutput,
        int candidates,
        int suppressed,
        int outsideWindow,
        String error) {

    /**
     * @param annotations what the digest's task said about this item, keyed by whatever the task asked
     *                    the model to record. Empty when there is no task, when the task summarises the
     *                    batch instead, or when the reply could not be read
     */
    public record Item(String entityId, String chunkId, String title, String uri, double score,
                       String snippet, java.util.Map<String, Object> annotations) {}

    public static DigestRunDto from(DigestRun run) {
        List<Item> items = new ArrayList<>(run.items().size());
        for (DigestRun.Item item : run.items()) {
            items.add(new Item(item.entityId(), item.chunkId(), item.title(), item.uri(),
                    item.score(), item.snippet(), item.annotations()));
        }
        return new DigestRunDto(run.id(), run.digestId(), run.ranAt(), items, run.taskOutput(),
                run.candidates(), run.suppressed(), run.outsideWindow(), run.error());
    }
}
