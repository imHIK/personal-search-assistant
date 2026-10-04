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
 * HYBRID runs both legs and fuses them with RRF, which avoids reconciling BM25 and cosine scales. Returns
 * chunks, uncapped; EntityGrouper makes the results. Each chunk records its rank in each leg, since the fused
 * score cannot say which leg was responsible.
 */
@ApplicationScoped
public class HybridRetriever implements Retriever {

    private final SearchIndex index;

    /**
     * Larger flattens the curve, so agreement between the legs matters more; smaller lets top ranks dominate.
     */
    @ConfigProperty(name = "app.search.rrf-k", defaultValue = "60")
    int rrfK;

    /**
     * On a conversational query BM25 matches incidental words. Lowering its weight is the blunt fix;
     * {@code app.search.lexical.minimum-should-match} is the sharp one.
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

    private static List<SearchHit> ranked(List<SearchHit> hits, boolean lexical) {
        List<SearchHit> out = new ArrayList<>(nonNull(hits).size());
        for (SearchHit hit : nonNull(hits)) {
            int rank = out.size() + 1;
            out.add(hit.withRanking(SearchHit.Ranking.of(hit.score())
                    .withLegRanks(lexical ? rank : null, lexical ? null : rank, hit.score())));
        }
        return out;
    }

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
