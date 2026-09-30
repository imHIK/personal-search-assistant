package io.personalassistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryEmbedderTest {

    private static final Retriever NOTHING = (query, vector, limit) -> List.<SearchHit>of();

    @Test
    void aRepeatedQueryIsEmbeddedOnce() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 16);

        embedder.retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.retrieve(NOTHING, SearchQuery.of("payroll"), 10);

        assertEquals(2, embeddings.queryCalls);
    }

    @Test
    void theLeastRecentlyUsedQueryIsEvictedFirst() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 2);

        embedder.retrieve(NOTHING, SearchQuery.of("a"), 10);
        embedder.retrieve(NOTHING, SearchQuery.of("b"), 10);
        embedder.retrieve(NOTHING, SearchQuery.of("a"), 10); // refreshes a
        embedder.retrieve(NOTHING, SearchQuery.of("c"), 10); // evicts b
        embedder.retrieve(NOTHING, SearchQuery.of("a"), 10);

        assertEquals(3, embeddings.queryCalls, "a stayed cached; b and c each cost one call");
    }

    @Test
    void aZeroSizeDisablesTheCache() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 0);

        embedder.retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.retrieve(NOTHING, SearchQuery.of("holidays"), 10);

        assertEquals(2, embeddings.queryCalls);
    }

    @Test
    void aLexicalQueryNeverEmbeds() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder.Retrieval retrieval = new QueryEmbedder(embeddings, 16)
                .retrieve(NOTHING, SearchQuery.of("holidays").withMode(SearchQuery.Mode.LEXICAL), 10);

        assertEquals(0, embeddings.queryCalls);
        assertNull(retrieval.vectorError(), "lexical by request is not a degradation");
    }
}
