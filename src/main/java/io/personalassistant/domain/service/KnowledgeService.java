package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Knowledge;
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
     * The inputs needed to register a knowledge.
     *
     * @param name   human-friendly label
     * @param type   connector type
     * @param auth   opaque credentials for the connector
     * @param inputs what to index (e.g. {@code rootPath} for LOCAL_FS)
     * @param config schedule/webhook/backfill settings, or null for defaults
     */
    record NewKnowledge(String name, SourceType type, Map<String, Object> auth,
                        Map<String, Object> inputs, Knowledge.Config config) {}
}
