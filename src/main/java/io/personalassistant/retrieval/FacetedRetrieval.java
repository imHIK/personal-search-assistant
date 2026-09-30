package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs a document query as several ordinary queries and fuses their rankings.
 *
 * <p>The facets come from {@link DocumentQueryPlanner}; this only fans them out. Each facet is retrieved
 * exactly as a typed query would be — same modes, same filters, same index — so nothing about the
 * retrieval path is special-cased for document queries. Fusing with RRF is what makes the result more
 * than a concatenation: a document matching three facets outranks one that matches a single facet very
 * well, which is the whole point of decomposing the source in the first place.
 */
@ApplicationScoped
public class FacetedRetrieval {

    private final DocumentQueryPlanner planner;
    private final Retriever retriever;

    /** Shared with the hybrid legs so {@code k} means one thing across the app. */
    @ConfigProperty(name = "app.search.rrf-k", defaultValue = "60")
    int rrfK;

    @Inject
    public FacetedRetrieval(DocumentQueryPlanner planner, Retriever retriever) {
        this.planner = planner;
        this.retriever = retriever;
    }

    /** Test-friendly constructor setting the tunable explicitly; the {@code @ConfigProperty} field is
     * package-private per house style and unreachable from another package's tests. */
    public FacetedRetrieval(DocumentQueryPlanner planner, Retriever retriever, int rrfK) {
        this(planner, retriever);
        this.rrfK = rrfK;
    }

    /**
     * Retrieve for {@code query}'s source document.
     *
     * @param limit candidates to return after fusion. Each facet is retrieved to the same depth, so a
     *              document ranked mid-list by several facets can still fuse its way to the top
     * @param vectors the search's embedding session, shared across facets so one refused embedding
     *                turns the remaining facets lexical instead of spending calls a spent quota refuses
     */
    public List<SearchHit> retrieve(SearchQuery query, int limit, QueryEmbedder.Session vectors) {
        List<String> facets = planner.facets(query);
        List<List<SearchHit>> rankings = new ArrayList<>(facets.size());

        for (String facet : facets) {
            // Embedded per facet, not once for the document — a facet is a short phrase, which is the
            // input shape query embeddings are meant for and the reason this decomposition helps at all.
            rankings.add(vectors.retrieve(retriever, query.withText(facet), limit));
        }
        if (rankings.isEmpty()) {
            return List.of();
        }
        // Equal weights: no facet is knowably more important than another, and weighting by the order a
        // model happened to emit them would be reading meaning into nothing.
        Map<String, Integer> facetsMatched = facetsMatched(rankings);
        return Rrf.fuse(rankings, List.of(), rrfK, limit).stream()
                .map(hit -> hit.withRanking(hit.ranking().withFacetsMatched(
                        facetsMatched.getOrDefault(hit.chunkId(), 0), hit.score())))
                .toList();
    }

    /**
     * How many facet rankings returned each chunk — the breadth that fusion rewards, recorded for
     * diagnosis. Per-leg ranks are cleared instead: each facet ran its own legs, so no single lexical or
     * vector rank describes the fused result.
     */
    private static Map<String, Integer> facetsMatched(List<List<SearchHit>> rankings) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<SearchHit> ranking : rankings) {
            Set<String> seen = new HashSet<>();
            for (SearchHit hit : ranking == null ? List.<SearchHit>of() : ranking) {
                if (seen.add(hit.chunkId())) {
                    counts.merge(hit.chunkId(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
