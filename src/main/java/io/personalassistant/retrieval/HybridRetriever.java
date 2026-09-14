package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default retriever. For HYBRID mode it runs lexical and vector retrieval independently and
 * merges them with Reciprocal Rank Fusion (RRF) — which avoids reconciling BM25 and cosine
 * score scales. LEXICAL/SEMANTIC delegate to a single primitive.
 */
@ApplicationScoped
public class HybridRetriever implements Retriever {

    private final SearchIndex index;

    /**
     * RRF's rank-smoothing constant. Larger flattens the contribution curve, so agreement between the
     * two legs matters more than either leg's exact ordering; smaller makes top ranks dominate.
     */
    @ConfigProperty(name = "app.search.rrf-k", defaultValue = "60")
    int rrfK;

    /**
     * Per-leg weights on the fused score. Equal weighting assumes both legs are equally trustworthy for
     * every query, which they are not: on a conversational query the BM25 leg matches incidental words
     * ("give", "all", "this", "year") and contributes a run of irrelevant candidates that can outrank the
     * vector leg's correct ones. Lowering the lexical weight is the blunt instrument for that; fixing the
     * query shape (`app.search.lexical.minimum-should-match`) is the sharp one.
     */
    @ConfigProperty(name = "app.search.rrf.lexical-weight", defaultValue = "1.0")
    double lexicalWeight;

    @ConfigProperty(name = "app.search.rrf.vector-weight", defaultValue = "1.0")
    double vectorWeight;

    /**
     * Cap on how many chunks one entity may contribute, applied after fusion. 0 = unlimited.
     *
     * <p>Deliberately off by default. It does buy source diversity when one document dominates, but it
     * actively harms the case where the right answer <em>is</em> many chunks of one document — a long
     * table, a single long report — which is exactly the query class that motivated this work. Reach for
     * it only when results are visibly swamped by one document.
     *
     * <p>This is the global floor; a caller that knows its corpus is record-shaped rather than
     * document-shaped overrides it per request via {@link SearchQuery#maxChunksPerEntity()}. That is
     * what makes "one result per job posting" expressible without forcing whole-document chunking,
     * which would blow the embedding provider's input limit on a long description.
     */
    @ConfigProperty(name = "app.search.max-chunks-per-entity", defaultValue = "0")
    int maxChunksPerEntity;

    @Inject
    public HybridRetriever(SearchIndex index) {
        this.index = index;
    }

    @Override
    public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
        List<SearchHit> hits = switch (query.mode()) {
            case LEXICAL -> index.lexicalSearch(query, limit);
            case SEMANTIC -> index.vectorSearch(query, queryVector, limit);
            case HYBRID -> fuse(
                    index.lexicalSearch(query, limit),
                    index.vectorSearch(query, queryVector, limit),
                    limit);
        };
        return capPerEntity(hits, limit, effectiveCap(query));
    }

    private List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> vector, int limit) {
        return Rrf.fuse(List.of(lexical, vector), List.of(lexicalWeight, vectorWeight), rrfK, limit);
    }

    /**
     * The per-request cap when one is set, else the configured global. A caller may pass 0 to mean
     * "unlimited" even where the global is set, so the override is honoured whenever it is present
     * rather than only when it is positive.
     */
    private int effectiveCap(SearchQuery query) {
        Integer override = query == null ? null : query.maxChunksPerEntity();
        return override == null ? maxChunksPerEntity : override;
    }

    /** Keep rank order, dropping a hit once its entity has already contributed its quota. */
    private List<SearchHit> capPerEntity(List<SearchHit> hits, int limit, int cap) {
        if (cap <= 0) {
            return hits;
        }
        Map<String, Integer> seen = new HashMap<>();
        List<SearchHit> out = new ArrayList<>(Math.min(hits.size(), limit));
        for (SearchHit hit : hits) {
            int count = seen.merge(hit.entityId() == null ? "" : hit.entityId(), 1, Integer::sum);
            if (count <= cap) {
                out.add(hit);
            }
        }
        return out;
    }
}
