package io.personalassistant.agent;

import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.storage.repository.EntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SourceTexts {

    private final EntityRepository entities;

    @Inject
    public SourceTexts(EntityRepository entities) {
        this.entities = entities;
    }

    /**
     * ENTITY mode collapses hits to one per entity. An entity with no inline text (file-backed) falls back to
     * its chunk text.
     */
    public Resolved resolve(TaskSpec task, List<SearchHit> hits) {
        if (task.sourceText() != TaskSpec.SourceText.ENTITY) {
            return new Resolved(hits, Map.of());
        }
        List<SearchHit> collapsed = new java.util.ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        java.util.Set<String> seenEntities = new java.util.HashSet<>();

        for (SearchHit hit : hits) {
            String entityId = hit.entityId();
            if (entityId != null && !seenEntities.add(entityId)) {
                continue;
            }
            collapsed.add(hit);
            if (entityId == null) {
                continue;
            }
            entities.findById(entityId)
                    .map(Entity::content)
                    .map(Entity.Content::text)
                    .filter(text -> text != null && !text.isBlank())
                    .ifPresent(text -> texts.put(hit.chunkId(), text));
        }
        return new Resolved(List.copyOf(collapsed), Map.copyOf(texts));
    }

    /** @param textByChunkId overriding text per chunk id; absent means the hit's own text */
    public record Resolved(List<SearchHit> hits, Map<String, String> textByChunkId) {}
}
