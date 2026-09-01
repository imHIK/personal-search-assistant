package io.personalassistant.agent;

import io.personalassistant.agent.llm.LlmProvider;
import io.personalassistant.agent.prompt.TaskSpec;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.testsupport.InMemoryEntityRepository;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Chunk-vs-entity source resolution, and the collapsing that entity mode requires. */
class SourceTextsTest {

    private final InMemoryEntityRepository entities = new InMemoryEntityRepository();
    private final SourceTexts sourceTexts = new SourceTexts(entities);

    private static TaskSpec task(TaskSpec.SourceText mode) {
        return new TaskSpec("t", "t", "default", 0, 0, mode, LlmProvider.ResponseFormat.TEXT);
    }

    private static SearchHit hit(String chunkId, String entityId, String text) {
        return new SearchHit(chunkId, entityId, "kn_1", 0, "Title", text, "snippet", "uri", 1.0, Map.of());
    }

    private void store(String entityId, String text) {
        Entity stored = TestData.ingestedText(entityId, "kn_1", entityId + ".txt", text);
        entities.store.put(stored.id(), stored);
    }

    @Test
    void chunkModeLeavesTheHitsAndTheirTextAlone() {
        List<SearchHit> hits = List.of(hit("c0", "ent_1", "chunk text"));

        SourceTexts.Resolved resolved = sourceTexts.resolve(task(TaskSpec.SourceText.CHUNK), hits);

        Assertions.assertEquals(hits, resolved.hits());
        Assertions.assertTrue(resolved.textByChunkId().isEmpty(),
                "no override means the prompt builder uses each hit's own text");
    }

    @Test
    void entityModeSubstitutesTheWholeDocument() {
        store("ent_1", "the entire posting, all of it");
        List<SearchHit> hits = List.of(hit("c0", "ent_1", "one slice of it"));

        SourceTexts.Resolved resolved = sourceTexts.resolve(task(TaskSpec.SourceText.ENTITY), hits);

        Assertions.assertEquals("the entire posting, all of it", resolved.textByChunkId().get("c0"));
    }

    @Test
    void entityModeCollapsesSeveralChunksOfOneDocument() {
        // Without this the same document is repeated verbatim, burning budget and inviting the model
        // to treat one item as several.
        store("ent_1", "the entire posting");
        store("ent_2", "a different posting");
        List<SearchHit> hits = List.of(
                hit("c0", "ent_1", "slice one"),
                hit("c1", "ent_1", "slice two"),
                hit("c2", "ent_2", "other"));

        SourceTexts.Resolved resolved = sourceTexts.resolve(task(TaskSpec.SourceText.ENTITY), hits);

        Assertions.assertEquals(List.of("c0", "c2"),
                resolved.hits().stream().map(SearchHit::chunkId).toList());
    }

    @Test
    void anEntityWithNoLoadableTextFallsBackToTheChunk() {
        // A file-backed entity keeps only a fileRef; a degraded source beats a missing one.
        List<SearchHit> hits = List.of(hit("c0", "ent_missing", "the chunk text"));

        SourceTexts.Resolved resolved = sourceTexts.resolve(task(TaskSpec.SourceText.ENTITY), hits);

        Assertions.assertEquals(1, resolved.hits().size());
        Assertions.assertNull(resolved.textByChunkId().get("c0"),
                "no override recorded, so the builder falls through to the hit's own text");
    }
}
