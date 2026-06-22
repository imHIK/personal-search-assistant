package io.personalassistant.storage.search.opensearch;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * OpenSearch adapter for {@link SearchIndex}. Stub for the design pass.
 *
 * <p>Implementation plan (next pass):
 * <ul>
 *   <li>Inject the OpenSearch Java client; target the {@code chunks} alias.</li>
 *   <li>{@code indexChunks}: {@code _bulk} with chunkId as the doc id.</li>
 *   <li>{@code lexicalSearch}: {@code match} on text/title + term filters.</li>
 *   <li>{@code vectorSearch}: {@code knn} on {@code embedding} + term filters.</li>
 *   <li>Apply sourceId/permission filters on every query.</li>
 *   <li>Index bootstrap + alias management per docs/opensearch-index.md.</li>
 * </ul>
 */
@ApplicationScoped
public class OpenSearchSearchIndex implements SearchIndex {

    @Override
    public void indexChunks(List<Chunk> chunks) {
        throw new UnsupportedOperationException("TODO: _bulk index by chunkId");
    }

    @Override
    public List<SearchHit> lexicalSearch(SearchQuery query, int limit) {
        throw new UnsupportedOperationException("TODO: BM25 match + filters");
    }

    @Override
    public List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit) {
        throw new UnsupportedOperationException("TODO: knn_vector search + filters");
    }

    @Override
    public void deleteByDocument(String documentId) {
        throw new UnsupportedOperationException("TODO: delete-by-query documentId");
    }

    @Override
    public void deleteBySource(String sourceId) {
        throw new UnsupportedOperationException("TODO: delete-by-query sourceId");
    }
}
