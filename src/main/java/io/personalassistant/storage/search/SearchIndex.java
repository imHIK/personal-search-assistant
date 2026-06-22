package io.personalassistant.storage.search;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import java.util.List;

/**
 * Port over the retrieval engine (OpenSearch today). Separates lexical and vector
 * primitives so a {@link io.personalassistant.retrieval.Retriever} can fuse them however
 * it likes. Swapping to Elasticsearch or a dedicated vector DB means one new adapter.
 */
public interface SearchIndex {

    /** Bulk index/update chunks (uses chunkId as doc id, so re-indexing overwrites). */
    void indexChunks(List<Chunk> chunks);

    /** Lexical BM25 retrieval over chunk text. */
    List<SearchHit> lexicalSearch(SearchQuery query, int limit);

    /** Semantic k-NN retrieval over the embedding vector. */
    List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit);

    void deleteByDocument(String documentId);

    void deleteBySource(String sourceId);
}
