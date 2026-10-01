package io.personalassistant.storage.search;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Task;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;
import java.util.Map;

/** Chunks live only here; the document id is the chunk id, so re-indexing overwrites. */
public interface SearchIndex {

    void indexChunks(List<Chunk> chunks);

    List<SearchHit> lexicalSearch(SearchQuery query, int limit);

    List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit);

    void deleteByEntity(String entityId);

    void deleteByKnowledge(String knowledgeId);

    /** Matches on both ids, since iterable ids are unique only within a knowledge. */
    void deleteByIterable(String knowledgeId, String iterableId);

    /**
     * Additive and idempotent: maps each {@code metadata.<field>} before the first document fixes its type
     * dynamically.
     *
     * @throws IllegalArgumentException if a field is already mapped with a conflicting type
     */
    void ensureMetadataFields(Map<String, Task.FieldType> fields);
}
