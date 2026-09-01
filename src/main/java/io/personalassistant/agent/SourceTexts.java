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

/**
 * Resolves the body of text each source entry in a prompt should carry.
 *
 * <p>Answering wants the retrieved chunk: the passage is what matched, and handing the model a whole
 * report to answer a question about one paragraph spends the context budget on noise. Judging an item
 * as a whole wants the opposite — scoring a job posting against a profile from one arbitrary slice of
 * its description is not a weaker answer, it is a meaningless one.
 *
 * <p>Kept out of {@code AnswerPromptBuilder} so that class stays a pure assembler: it takes text it is
 * given, which is what lets its budgeting and truncation logic be tested without a repository.
 */
@ApplicationScoped
public class SourceTexts {

    private final EntityRepository entities;

    @Inject
    public SourceTexts(EntityRepository entities) {
        this.entities = entities;
    }

    /**
     * The hits to render, and the text to render for each, keyed by chunk id.
     *
     * <p>In {@code ENTITY} mode the hits are collapsed to one per entity — several chunks of one
     * document would otherwise repeat that document verbatim, burning the budget and inviting the model
     * to treat one item as several. An entity whose text cannot be loaded (a file-backed entity keeps
     * only a {@code fileRef}, and the bytes are not re-read here) falls back to its chunk text, which is
     * a degraded source rather than a missing one.
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

    /**
     * @param hits          the hits to render, in order
     * @param textByChunkId overriding text per chunk id; an absent entry means "use the hit's own text"
     */
    public record Resolved(List<SearchHit> hits, Map<String, String> textByChunkId) {}
}
