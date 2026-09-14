package io.personalassistant.app;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import io.personalassistant.retrieval.DuplicateCollapser;
import io.personalassistant.retrieval.FacetedRetrieval;
import io.personalassistant.retrieval.Reranker;
import io.personalassistant.retrieval.Retriever;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Read-path orchestration: embed the query (unless purely lexical), retrieve candidates,
 * rerank, and optionally synthesize a grounded answer. Wires the retrieval ports together;
 * the concrete behaviour follows whatever adapters are installed.
 */
@ApplicationScoped
public class DefaultSearchService implements SearchService {

    private static final Logger LOG = Logger.getLogger(DefaultSearchService.class.getName());

    private final EmbeddingProvider embeddings;
    private final Retriever retriever;
    private final FacetedRetrieval faceted;
    private final Reranker reranker;
    private final DuplicateCollapser duplicates;
    private final SearchAgent agent;

    /**
     * Upper bound on {@code topK}. Clamped here rather than rejected at the DTO because the ceiling is
     * config-driven policy, matching how the entity-listing limit is clamped in the knowledge service.
     * It matters: {@code topK} is multiplied before it becomes the OpenSearch {@code size} and the knn
     * {@code k}, so an unbounded value asks the cluster for an unbounded result set.
     */
    @ConfigProperty(name = "app.search.max-top-k", defaultValue = "100")
    int maxTopK;

    /**
     * How many candidates each retrieval leg fetches per requested result. Over-fetching gives fusion
     * and reranking something to work with; too little and a chunk that only one leg ranks well never
     * reaches the fusion step at all.
     */
    @ConfigProperty(name = "app.search.candidate-multiplier", defaultValue = "4")
    int candidateMultiplier;

    @Inject
    public DefaultSearchService(EmbeddingProvider embeddings,
                                Retriever retriever,
                                FacetedRetrieval faceted,
                                Reranker reranker,
                                DuplicateCollapser duplicates,
                                SearchAgent agent) {
        this.embeddings = embeddings;
        this.retriever = retriever;
        this.faceted = faceted;
        this.reranker = reranker;
        this.duplicates = duplicates;
        this.agent = agent;
    }

    @Override
    public SearchResponse search(SearchQuery query) {
        long start = System.currentTimeMillis();
        int topK = Math.clamp(query.topK(), 1, Math.max(maxTopK, 1));

        int candidateLimit = topK * Math.max(candidateMultiplier, 1);
        // A document query is decomposed into facets and fused; a typed query embeds its own text once.
        // Both produce the same candidate shape, so everything downstream is unchanged.
        List<SearchHit> candidates;
        if (query.isDocumentQuery()) {
            candidates = faceted.retrieve(query, candidateLimit);
        } else {
            float[] queryVector = query.mode() == SearchQuery.Mode.LEXICAL
                    ? null
                    : embeddings.embedQuery(query.text()).vector();
            candidates = retriever.retrieve(query, queryVector, candidateLimit);
        }
        // Collapse before reranking, not after: reranking trims to topK, so collapsing afterwards would
        // return fewer than topK results — the duplicates would have eaten slots that a distinct hit
        // further down the candidate list could have filled.
        if (query.collapseDuplicates()) {
            candidates = duplicates.collapse(candidates);
        }
        List<SearchHit> ranked = reranker.rerank(query.text(), candidates, topK);

        String answer = null;
        String answerError = null;
        if (query.answer()) {
            try {
                answer = agent.answer(query, ranked);
            } catch (RuntimeException e) {
                // Retrieval has already succeeded here. Letting this propagate turned an unavailable or
                // misconfigured LLM into a failed search that threw away every hit it had just found —
                // the console papered over it by retrying without the answer flag, but any other API
                // consumer simply lost the results. Report the failure alongside the hits instead.
                answerError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.log(Level.WARNING, "Answer synthesis failed; returning hits without an answer", e);
            }
        }
        return new SearchResponse(ranked, answer, answerError, System.currentTimeMillis() - start);
    }
}
