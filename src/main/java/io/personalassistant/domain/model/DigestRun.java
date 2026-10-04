package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The runs are also the already-seen set: an item is new when no earlier run holds it, which an index
 * timestamp cannot tell once retention re-creates entities.
 *
 * @param taskOutput the task's reply verbatim, kept even once read into annotations, so an unparseable reply
 *                   can be inspected
 * @param candidates results the search returned before already-seen ones were dropped
 * @param suppressed how many of those were dropped as already reported
 * @param outsideWindow results the same search finds without the window, counted only when the windowed
 *                      search found nothing
 * @param error why the run failed; a failed run has no items
 * @param taskError why the task failed after the search succeeded; the items are kept
 */
public record DigestRun(
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

    public DigestRun {
        items = items == null ? List.of() : List.copyOf(items);
        if (candidates < 0) {
            candidates = 0;
        }
        if (suppressed < 0) {
            suppressed = 0;
        }
        if (outsideWindow < 0) {
            outsideWindow = 0;
        }
    }

    public DigestRun(String id, String digestId, Instant ranAt, List<Item> items, String taskOutput,
                     String error) {
        this(id, digestId, ranAt, items, taskOutput, items == null ? 0 : items.size(), 0, 0, error, null);
    }

    /**
     * @param entityId the key newness is computed on
     * @param annotations what the task said about this item, keyed by whatever it asked for; open because
     *                    tasks are user-written
     */
    public record Item(
            String entityId,
            String chunkId,
            String title,
            String uri,
            double score,
            String snippet,
            Map<String, Object> annotations) {

        public Item {
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }

        public Item(String entityId, String chunkId, String title, String uri, double score,
                    String snippet) {
            this(entityId, chunkId, title, uri, score, snippet, Map.of());
        }

        public Item withAnnotations(Map<String, Object> values) {
            return new Item(entityId, chunkId, title, uri, score, snippet, values);
        }
    }
}
