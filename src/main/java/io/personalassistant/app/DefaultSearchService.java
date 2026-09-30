package io.personalassistant.app;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.retrieval.DuplicateCollapser;
import io.personalassistant.retrieval.EntityGrouper;
import io.personalassistant.retrieval.QueryEmbedder;
import io.personalassistant.retrieval.RecencyBoost;
import io.personalassistant.retrieval.Reranker;
import io.personalassistant.retrieval.Retriever;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DefaultSearchService implements SearchService {

    private static final Logger LOG = Logger.getLogger(DefaultSearchService.class.getName());

    private final QueryEmbedder embeddings;
    private final Retriever retriever;
    private final Reranker reranker;
    private final EntityGrouper grouper;
    private final RecencyBoost recency;
    private final DuplicateCollapser duplicates;
    private final SearchAgent agent;

    @ConfigProperty(name = "app.search.max-top-k", defaultValue = "100")
    int maxTopK;

    /**
     * Retrieval returns chunks and a matching entity brings several, so the pool is sized in chunks per
     * wanted entity.
     */
    @ConfigProperty(name = "app.search.candidate-multiplier", defaultValue = "10")
    int candidateMultiplier;

    @ConfigProperty(name = "app.search.min-candidates", defaultValue = "100")
    int minCandidates;

    /**
     * Becomes the OpenSearch {@code size} and knn {@code k} on every leg, so it bounds the cost of one
     * request.
     */
    @ConfigProperty(name = "app.search.max-candidates", defaultValue = "500")
    int maxCandidates;

    @Inject
    public DefaultSearchService(QueryEmbedder embeddings,
                                Retriever retriever,
                                Reranker reranker,
                                EntityGrouper grouper,
                                RecencyBoost recency,
                                DuplicateCollapser duplicates,
                                SearchAgent agent) {
        this.embeddings = embeddings;
        this.retriever = retriever;
        this.reranker = reranker;
        this.grouper = grouper;
        this.recency = recency;
        this.duplicates = duplicates;
        this.agent = agent;
    }

    @Override
    public SearchResponse search(SearchQuery query) {
        long start = System.currentTimeMillis();
        int topK = Math.clamp(query.topK(), 1, Math.max(maxTopK, 1));

        int candidateLimit = candidateLimit(topK);
        QueryEmbedder.Retrieval retrieval = embeddings.retrieve(retriever, query, candidateLimit);
        List<SearchHit> candidates = retrieval.hits();
        // Chunks become results here: everything after works on entities, so topK counts items.
        List<SearchHit> results = recency.apply(grouper.group(candidates, query.maxChunksPerEntity()));
        // Collapse before reranking: reranking trims to topK, and duplicates collapsed afterwards would leave
        // fewer than topK results.
        if (query.collapseDuplicates()) {
            results = duplicates.collapse(results);
        }
        List<SearchHit> ranked = reranker.rerank(query.text(), results, topK);

        String answer = null;
        String answerError = null;
        if (query.answer()) {
            try {
                answer = agent.answer(query, ranked);
            } catch (RuntimeException e) {
                // The hits are already found: an LLM failure is reported alongside them, not thrown.
                answerError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.log(Level.WARNING, "Answer synthesis failed; returning hits without an answer", e);
            }
        }
        return new SearchResponse(ranked, answer, answerError, retrieval.vectorError(),
                System.currentTimeMillis() - start);
    }

    int candidateLimit(int topK) {
        int ceiling = Math.max(maxCandidates, 1);
        int floor = Math.min(Math.max(minCandidates, 1), ceiling);
        return Math.clamp((long) topK * Math.max(candidateMultiplier, 1), floor, ceiling);
    }
}
