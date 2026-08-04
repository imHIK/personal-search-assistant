package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.EntityStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Use-case port for the knowledge lifecycle: add (validate → anchor → discover iterables →
 * create cursors → activate), pause/resume, and tear down. Keeps the REST layer free of
 * orchestration detail.
 */
public interface KnowledgeService {

    /** Validate, persist, discover iterables, create cursors, and activate a new knowledge. */
    Knowledge add(NewKnowledge request);

    /**
     * Apply a partial edit to an existing knowledge, routed by what actually changed
     * (see {@code knowledge-edit-design.md}). Config-class fields (name / schedule / webhook /
     * backfill-off) are written in place; provisioning-class fields (auth / inputs / backfill-on)
     * pause the knowledge, re-verify and re-discover the source, reconcile cursors
     * (park-don't-purge on shrink), re-walk iterables whose membership signature moved, and restore
     * status. Works across any lifecycle status.
     *
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     * @throws IllegalArgumentException          if the patch tries to change the immutable connector
     *                                           {@code type}
     * @throws IllegalStateException             if the knowledge is {@code DELETED} (cannot be edited)
     * @return the updated knowledge (in {@code ERROR} with {@code lastError} set if re-verify/
     *         re-discover failed, mirroring {@link #add})
     */
    Knowledge update(String id, KnowledgePatch patch);

    Optional<Knowledge> get(String id);

    List<Knowledge> list();

    void pause(String id);

    void resume(String id);

    /** Soft-delete and tear down: stop scheduling, remove chunks, entities and cursors. */
    void delete(String id);

    /** Manually re-arm forward cursors (incremental trigger / webhook). Returns count armed. */
    int triggerSync(String id);

    /**
     * Re-discover the knowledge's iterables and create cursors for any new ones (idempotent).
     * Used by the discovery scheduler for sources whose iterables grow over time, and callable
     * on demand. Existing cursors are left untouched.
     *
     * @return the number of new cursors created
     */
    int reconcileCursors(String id);

    /**
     * Page this knowledge's entities newest-first, for the console's entity browser.
     *
     * <p>{@code limit} is clamped to {@code 1..200} ({@code <= 0} means the default of 50) so a
     * caller cannot ask for an unbounded page.
     *
     * @param status optional status filter; {@code null} means all statuses
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     * @throws IllegalArgumentException          if {@code offset} is negative
     */
    EntityPage listEntities(String id, EntityStatus status, int limit, int offset);

    /**
     * This knowledge's cursors — one per {@code (iterableId, direction)} — ordered by
     * {@code (iterableId, direction)} so the console renders them stably. This is the only view of
     * real sync progress: the knowledge's {@code stats} say how many entities exist, but only the
     * cursors say whether the backward walk has finished.
     *
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     */
    List<Cursor> listCursors(String id);

    /**
     * One page of a knowledge's entities.
     *
     * @param items  the page, newest-first
     * @param total  how many entities match the filter, so the caller can render page controls
     * @param limit  the clamped page size actually applied
     * @param offset the offset actually applied
     */
    record EntityPage(List<EntitySummary> items, long total, int limit, int offset) {}

    /**
     * The inputs needed to register a knowledge.
     *
     * @param name         human-friendly label
     * @param type         connector type
     * @param connectionId the {@link io.personalassistant.domain.model.Connection} to authenticate
     *                     through, or null to use the type's default (ignored by no-auth connectors)
     * @param auth         inline credentials fallback (normally empty when a connection is used)
     * @param inputs       what to index (e.g. {@code rootPath} for LOCAL_FS)
     * @param config       schedule/webhook/backfill settings, or null for defaults
     */
    record NewKnowledge(String name, SourceType type, String connectionId, Map<String, Object> auth,
                        Map<String, Object> inputs, Knowledge.Config config) {}
}
