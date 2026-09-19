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
 *
 * <p>It returns <em>chunks</em>, uncapped; {@link EntityGrouper} turns them into one result per entity
 * downstream of every retrieval path. A per-entity cap used to be applied here, to the already-fetched
 * pool, and starved the result set: 40 chunks over job postings of ~7 chunks each held about 6 distinct
 * postings, so "one per posting" returned 6 results for a topK of 10 and nothing ever fetched more.
 *
 * <p>Every chunk leaves with {@link SearchHit.Ranking#lexicalRank()} / {@code vectorRank()} recorded —
 * where each leg placed it — because the fused score alone cannot say which leg was responsible for a
 * wrong result.
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

    @Inject
    public HybridRetriever(SearchIndex index) {
        this.index = index;
    }

    @Override
    public List<SearchHit> retrieve(SearchQuery query, float[] queryVector, int limit) {
        return switch (query.mode()) {
            case LEXICAL -> ranked(index.lexicalSearch(query, limit), true);
            case SEMANTIC -> ranked(index.vectorSearch(query, queryVector, limit), false);
            case HYBRID -> fuse(
                    index.lexicalSearch(query, limit),
                    index.vectorSearch(query, queryVector, limit),
                    limit);
        };
    }

    private List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> vector, int limit) {
        Map<String, Integer> lexicalRanks = ranks(lexical);
        Map<String, Integer> vectorRanks = ranks(vector);
        return Rrf.fuse(List.of(nonNull(lexical), nonNull(vector)), List.of(lexicalWeight, vectorWeight), rrfK, limit)
                .stream()
                .map(hit -> hit.withRanking(hit.ranking().withLegRanks(
                        lexicalRanks.get(hit.chunkId()), vectorRanks.get(hit.chunkId()), hit.score())))
                .toList();
    }

    /** A single leg's results, each recording its own position in that leg. */
    private static List<SearchHit> ranked(List<SearchHit> hits, boolean lexical) {
        List<SearchHit> out = new ArrayList<>(nonNull(hits).size());
        for (SearchHit hit : nonNull(hits)) {
            int rank = out.size() + 1;
            out.add(hit.withRanking(SearchHit.Ranking.of(hit.score())
                    .withLegRanks(lexical ? rank : null, lexical ? null : rank, hit.score())));
        }
        return out;
    }

    /** 1-based position of each chunk in one leg's list; the first occurrence wins. */
    private static Map<String, Integer> ranks(List<SearchHit> hits) {
        Map<String, Integer> ranks = new HashMap<>();
        List<SearchHit> list = nonNull(hits);
        for (int i = 0; i < list.size(); i++) {
            ranks.putIfAbsent(list.get(i).chunkId(), i + 1);
        }
        return ranks;
    }

    private static List<SearchHit> nonNull(List<SearchHit> hits) {
        return hits == null ? List.of() : hits;
    }
}
