package io.personalassistant.app;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import io.personalassistant.retrieval.Reranker;
import io.personalassistant.retrieval.Retriever;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Read-path orchestration: embed the query (unless purely lexical), retrieve candidates,
 * rerank, and optionally synthesize a grounded answer. Wires the retrieval ports together;
 * the concrete behaviour follows whatever adapters are installed.
 */
@ApplicationScoped
public class DefaultSearchService implements SearchService {

    private final EmbeddingProvider embeddings;
    private final Retriever retriever;
    private final Reranker reranker;
    private final io.personalassistant.agent.SearchAgent agent;

    @Inject
    public DefaultSearchService(EmbeddingProvider embeddings,
                                Retriever retriever,
                                Reranker reranker,
                                io.personalassistant.agent.SearchAgent agent) {
        this.embeddings = embeddings;
        this.retriever = retriever;
        this.reranker = reranker;
        this.agent = agent;
    }

    @Override
    public SearchResponse search(SearchQuery query) {
        long start = System.currentTimeMillis();

        float[] queryVector = query.mode() == SearchQuery.Mode.LEXICAL
                ? null
                : embeddings.embed(query.text()).vector();

        int candidateLimit = Math.max(query.topK() * 4, query.topK());
        List<SearchHit> candidates = retriever.retrieve(query, queryVector, candidateLimit);
        List<SearchHit> ranked = reranker.rerank(query.text(), candidates, query.topK());

        String answer = query.answer() ? agent.answer(query, ranked) : null;
        return new SearchResponse(ranked, answer, System.currentTimeMillis() - start);
    }
}
