package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.EntityQuery;
import io.personalassistant.domain.model.EntitySummary;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeService {

    Knowledge add(NewKnowledge request);

    /**
     * Routed by what changed: config fields are written in place; auth, inputs and backfill-on pause,
     * re-verify, re-discover, reconcile and re-walk.
     *
     * @return the updated knowledge, in ERROR with lastError when re-verify or re-discover failed
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     * @throws IllegalArgumentException if it tries to change the connector type
     * @throws IllegalStateException if the knowledge is DELETED
     */
    Knowledge update(String id, KnowledgePatch patch);

    Optional<Knowledge> get(String id);

    List<Knowledge> list();

    void pause(String id);

    void resume(String id);

    void delete(String id);

    int triggerSync(String id);

    /**
     * Idempotent; existing cursors are untouched.
     *
     * @return the number of new cursors created
     */
    int reconcileCursors(String id);

    /**
     * {@code limit} is clamped to 1..200, and {@code <= 0} means 50.
     *
     * @param query null reads as {@link EntityQuery#all()}
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     * @throws IllegalArgumentException if {@code offset} is negative
     */
    EntityPage listEntities(String id, EntityQuery query, int limit, int offset);

    /**
     * Ordered by (iterableId, direction).
     *
     * @throws java.util.NoSuchElementException if no knowledge with {@code id} exists
     */
    List<Cursor> listCursors(String id);

    record EntityPage(List<EntitySummary> items, long total, int limit, int offset) {}

    /**
     * @param connectionId null uses the type's default connection
     * @param config null for defaults
     */
    record NewKnowledge(String name, SourceType type, String connectionId, Map<String, Object> auth,
                        Map<String, Object> inputs, Knowledge.Config config) {}
}
