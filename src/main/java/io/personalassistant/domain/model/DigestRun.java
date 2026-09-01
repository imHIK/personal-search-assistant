package io.personalassistant.domain.model;

import java.time.Instant;
import java.util.List;

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
 * @param taskOutput the LLM task's reply, or null when the digest names no task
 * @param error      why the run failed, or null. A failed run is still recorded: a digest that has been
 *                   silently erroring for a week should be visible as such rather than just quiet
 */
public record DigestRun(
        String id,
        String digestId,
        Instant ranAt,
        List<Item> items,
        String taskOutput,
        String error) {

    public DigestRun {
        items = items == null ? List.of() : List.copyOf(items);
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
     */
    public record Item(
            String entityId,
            String chunkId,
            String title,
            String uri,
            double score,
            String snippet) {}
}
