package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One execution of a {@link Digest}: what it found, and what the optional LLM task made of it.
 *
 * <p>Runs are kept rather than overwritten for two reasons. They are the digest's history — "what did
 * you send me on Tuesday" — and they are also how <em>newness</em> is computed: an item counts as new
 * when it is inside the window and absent from every earlier run. That second use is load-bearing.
 * Deriving newness from an index timestamp alone would resurface an item every time it was re-indexed,
 * which retention makes routine — an entity that ages out and is re-created by the next walk gets a
 * fresh {@code indexedAt} while being the same posting the user already saw.
 *
 * @param id         stable id, {@code run_...}
 * @param digestId   the digest this ran
 * @param ranAt      when it ran
 * @param items      what it found, newest-first by rank
 * @param taskOutput the LLM task's reply, verbatim, or null when the digest names no task. Kept even
 *                   when it has been read into {@link Item#annotations} — a reply that would not parse
 *                   is then still there to look at, which is the difference between a task that needs
 *                   rewording and one that is simply broken
 * @param candidates how many results the search returned <em>before</em> already-seen ones were
 *                   dropped
 * @param suppressed how many of those were dropped as already reported. Together with
 *                   {@code candidates} these separate three outcomes that otherwise all look like an
 *                   empty run: nothing matched, everything matched was already seen, and the search
 *                   itself found nothing new. "Why is my digest quiet?" is unanswerable without them
 * @param outsideWindow how many results the same search finds with the look-back window removed,
 *                   counted only when the windowed search returned nothing. The window filters on
 *                   {@code indexedAt}, so a corpus that is fully ingested and then left alone falls out
 *                   of a short window entirely and every run afterwards is empty — permanently, and
 *                   for a reason no counter above can express. A non-zero value here is the difference
 *                   between "your query matches nothing" and "widen the look-back"
 * @param error      why the run failed, or null. A failed run is still recorded: a digest that has been
 *                   silently erroring for a week should be visible as such rather than just quiet
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
        String error) {

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

    /** Runs recorded before the counters existed, and tests that do not care about them. */
    public DigestRun(String id, String digestId, Instant ranAt, List<Item> items, String taskOutput,
                     String error) {
        this(id, digestId, ranAt, items, taskOutput, items == null ? 0 : items.size(), 0, 0, error);
    }

    /**
     * A single result, flattened for storage. Deliberately a projection rather than the whole
     * {@code SearchHit}: a run is a historical record, and storing full chunk text would grow the
     * collection without bound while duplicating what the entity already holds.
     *
     * @param entityId   what was found — the key newness is computed on, because a chunk id changes
     *                   when a document is re-chunked while the document itself has not changed
     * @param chunkId    the passage that matched
     * @param title      display title at the time of the run
     * @param uri        where to open it
     * @param score      fused retrieval score
     * @param snippet    short display excerpt
     * @param annotations what the digest's task said about <em>this</em> item, keyed by whatever the
     *                   task asked the model to record — a score, a one-line reason, a caveat. Empty
     *                   for a digest with no task, for a task that summarises the batch rather than
     *                   describing each item, and for a reply that could not be read.
     *                   <p>Deliberately an open map rather than named fields. The keys come from the
     *                   task, and tasks are user-written: typing them here would mean a schema change
     *                   for every new question someone wants asked of their results, and would make
     *                   the console branch on which task produced a run
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

        /** An item as retrieval produced it, before any task has described it. */
        public Item(String entityId, String chunkId, String title, String uri, double score,
                    String snippet) {
            this(entityId, chunkId, title, uri, score, snippet, Map.of());
        }

        public Item withAnnotations(Map<String, Object> values) {
            return new Item(entityId, chunkId, title, uri, score, snippet, values);
        }
    }
}
