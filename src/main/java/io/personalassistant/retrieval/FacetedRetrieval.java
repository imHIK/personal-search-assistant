package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
    private final EmbeddingProvider embeddings;

    /** Shared with the hybrid legs so {@code k} means one thing across the app. */
    @ConfigProperty(name = "app.search.rrf-k", defaultValue = "60")
    int rrfK;

    @Inject
    public FacetedRetrieval(DocumentQueryPlanner planner, Retriever retriever,
                            EmbeddingProvider embeddings) {
        this.planner = planner;
        this.retriever = retriever;
        this.embeddings = embeddings;
    }

    /** Test-friendly constructor setting the tunable explicitly; the {@code @ConfigProperty} field is
     * package-private per house style and unreachable from another package's tests. */
    public FacetedRetrieval(DocumentQueryPlanner planner, Retriever retriever,
                            EmbeddingProvider embeddings, int rrfK) {
        this(planner, retriever, embeddings);
        this.rrfK = rrfK;
    }

    /**
     * Retrieve for {@code query}'s source document.
     *
     * @param limit candidates to return after fusion. Each facet is retrieved to the same depth, so a
     *              document ranked mid-list by several facets can still fuse its way to the top
     */
    public List<SearchHit> retrieve(SearchQuery query, int limit) {
        List<String> facets = planner.facets(query);
        List<List<SearchHit>> rankings = new ArrayList<>(facets.size());

        for (String facet : facets) {
            SearchQuery facetQuery = query.withText(facet);
            // Embedded per facet, not once for the document — a facet is a short phrase, which is the
            // input shape query embeddings are meant for and the reason this decomposition helps at all.
            float[] vector = facetQuery.mode() == SearchQuery.Mode.LEXICAL
                    ? null
                    : embeddings.embedQuery(facet).vector();
            rankings.add(retriever.retrieve(facetQuery, vector, limit));
        }
        if (rankings.isEmpty()) {
            return List.of();
        }
        // Equal weights: no facet is knowably more important than another, and weighting by the order a
        // model happened to emit them would be reading meaning into nothing.
        return Rrf.fuse(rankings, List.of(), rrfK, limit);
    }
}
