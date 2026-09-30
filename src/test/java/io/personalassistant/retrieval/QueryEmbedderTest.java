package io.personalassistant.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.testsupport.FakeEmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The query-vector cache: a repeated query costs no embedding call, and a disabled cache costs one each. */
class QueryEmbedderTest {

    private static final Retriever NOTHING = (query, vector, limit) -> List.<SearchHit>of();

    @Test
    void aRepeatedQueryIsEmbeddedOnce() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 16);

        embedder.session().retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.session().retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.session().retrieve(NOTHING, SearchQuery.of("payroll"), 10);

        assertEquals(2, embeddings.queryCalls);
    }

    @Test
    void theLeastRecentlyUsedQueryIsEvictedFirst() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 2);

        embedder.session().retrieve(NOTHING, SearchQuery.of("a"), 10);
        embedder.session().retrieve(NOTHING, SearchQuery.of("b"), 10);
        embedder.session().retrieve(NOTHING, SearchQuery.of("a"), 10); // refreshes a
        embedder.session().retrieve(NOTHING, SearchQuery.of("c"), 10); // evicts b
        embedder.session().retrieve(NOTHING, SearchQuery.of("a"), 10);

        assertEquals(3, embeddings.queryCalls, "a stayed cached; b and c each cost one call");
    }

    @Test
    void aZeroSizeDisablesTheCache() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder embedder = new QueryEmbedder(embeddings, 0);

        embedder.session().retrieve(NOTHING, SearchQuery.of("holidays"), 10);
        embedder.session().retrieve(NOTHING, SearchQuery.of("holidays"), 10);

        assertEquals(2, embeddings.queryCalls);
    }

    @Test
    void aLexicalQueryNeverEmbeds() {
        FakeEmbeddingProvider embeddings = new FakeEmbeddingProvider(768);
        QueryEmbedder.Session session = new QueryEmbedder(embeddings, 16).session();

        session.retrieve(NOTHING, SearchQuery.of("holidays").withMode(SearchQuery.Mode.LEXICAL), 10);

        assertEquals(0, embeddings.queryCalls);
        assertNull(session.vectorError(), "lexical by request is not a degradation");
    }
}
