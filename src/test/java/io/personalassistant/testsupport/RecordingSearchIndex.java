package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.search.SearchIndex;
import java.util.ArrayList;
import java.util.List;

/** A {@link SearchIndex} that records calls, for asserting indexing behaviour without OpenSearch. */
public class RecordingSearchIndex implements SearchIndex {

    public final List<Chunk> indexed = new ArrayList<>();
    public final List<String> deletedEntities = new ArrayList<>();
    public final List<String> deletedKnowledge = new ArrayList<>();
    /** Recorded as {@code "knowledgeId/iterableId"} for assertion convenience. */
    public final List<String> deletedIterables = new ArrayList<>();
    public List<SearchHit> lexicalResult = List.of();
    public List<SearchHit> vectorResult = List.of();
    /** When set, {@link #indexChunks} throws it — stands in for a rejected OpenSearch bulk. */
    public RuntimeException indexChunksFailure;

    @Override
    public void indexChunks(List<Chunk> chunks) {
        if (indexChunksFailure != null) {
            throw indexChunksFailure;
        }
        indexed.addAll(chunks);
    }

    @Override
    public List<SearchHit> lexicalSearch(SearchQuery query, int limit) {
        return lexicalResult;
    }

    @Override
    public List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit) {
        return vectorResult;
    }

    @Override
    public void deleteByEntity(String entityId) {
        deletedEntities.add(entityId);
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        deletedKnowledge.add(knowledgeId);
    }

    @Override
    public void deleteByIterable(String knowledgeId, String iterableId) {
        deletedIterables.add(knowledgeId + "/" + iterableId);
    }
}
