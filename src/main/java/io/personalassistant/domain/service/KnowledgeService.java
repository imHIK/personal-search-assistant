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

    Optional<Knowledge> get(String id);

    List<Knowledge> list();

    void pause(String id);

    void resume(String id);

    /** Soft-delete and tear down: stop scheduling, remove chunks, entities and cursors. */
    void delete(String id);

    /** Manually re-arm forward cursors (incremental trigger / webhook). Returns count armed. */
    int triggerSync(String id);

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
