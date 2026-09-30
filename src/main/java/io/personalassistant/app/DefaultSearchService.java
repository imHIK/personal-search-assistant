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

/**
 * Read-path orchestration: embed the query (unless purely lexical), retrieve chunk candidates, group
 * them into one result per entity, boost fresh results, collapse duplicates, rerank, and optionally
 * synthesize a grounded answer. Wires the retrieval ports together; the concrete behaviour follows
 * whatever adapters are installed.
 */
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

    /**
     * Upper bound on {@code topK}. Clamped here rather than rejected at the DTO because the ceiling is
     * config-driven policy, matching how the entity-listing limit is clamped in the knowledge service.
     */
    @ConfigProperty(name = "app.search.max-top-k", defaultValue = "100")
    int maxTopK;

    /**
     * How many chunk candidates each retrieval leg fetches per requested result.
     *
     * <p>Results are entities but retrieval returns chunks, and a matching entity brings several of its
     * chunks into the pool at once — so the pool must be sized in chunks for the number of
     * <em>entities</em> wanted. At the old 4, a topK of 10 bought 40 chunks, which over job postings of
     * ~7 chunks each held about 6 distinct postings: fewer results than asked for, with nothing left to
     * fill the rest.
     */
    @ConfigProperty(name = "app.search.candidate-multiplier", defaultValue = "10")
    int candidateMultiplier;

    /** Floor on the candidate pool, so a small topK still fetches enough chunks to fill itself with entities. */
    @ConfigProperty(name = "app.search.min-candidates", defaultValue = "100")
    int minCandidates;

    /**
     * Ceiling on the candidate pool. It becomes the OpenSearch {@code size} and knn {@code k} on every leg,
     * so this is what bounds the cost of one request.
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
        // Chunks become results here. Everything after this line — freshness, collapsing, reranking, the
        // topK trim and the answer — works on entities, so topK counts items and one item can no longer
        // fill every slot with its own chunks.
        List<SearchHit> results = recency.apply(grouper.group(candidates, query.maxChunksPerEntity()));
        // Collapse before reranking, not after: reranking trims to topK, so collapsing afterwards would
        // return fewer than topK results — the duplicates would have eaten slots that a distinct hit
        // further down the candidate list could have filled.
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
                // Retrieval has already succeeded here. Letting this propagate turned an unavailable or
                // misconfigured LLM into a failed search that threw away every hit it had just found —
                // the console papered over it by retrying without the answer flag, but any other API
                // consumer simply lost the results. Report the failure alongside the hits instead.
                answerError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.log(Level.WARNING, "Answer synthesis failed; returning hits without an answer", e);
            }
        }
        return new SearchResponse(ranked, answer, answerError, retrieval.vectorError(),
                System.currentTimeMillis() - start);
    }

    /** {@code topK × candidate-multiplier}, held between the configured floor and ceiling. */
    // Package-private for tests.
    int candidateLimit(int topK) {
        int ceiling = Math.max(maxCandidates, 1);
        int floor = Math.min(Math.max(minCandidates, 1), ceiling);
        return Math.clamp((long) topK * Math.max(candidateMultiplier, 1), floor, ceiling);
    }
}
