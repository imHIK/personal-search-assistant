package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One result per entity, carrying its other matching chunks: ranking chunks let one item fill every slot with
 * its siblings. An entity scores as its best chunk plus a small share of its further matches, bounded by the
 * per-entity cap.
 */
@ApplicationScoped
public class EntityGrouper {

    /** 0 means every matching chunk in the pool. A request may override it; a digest sends 1. */
    @ConfigProperty(name = "app.search.max-chunks-per-entity", defaultValue = "3")
    int maxChunksPerEntity;

    /** 0 ranks on the best chunk alone. */
    @ConfigProperty(name = "app.search.grouping.extra-match-weight", defaultValue = "0.1")
    double extraMatchWeight;

    public EntityGrouper() {
    }

    public EntityGrouper(int maxChunksPerEntity, double extraMatchWeight) {
        this.maxChunksPerEntity = maxChunksPerEntity;
        this.extraMatchWeight = extraMatchWeight;
    }

    /** @param override per-request cap, or null for the configured one */
    public List<SearchHit> group(List<SearchHit> chunks, Integer override) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int cap = override == null ? maxChunksPerEntity : override;

        Map<String, List<SearchHit>> byEntity = new LinkedHashMap<>();
        for (SearchHit chunk : chunks) {
            // A hit with no entity stands alone rather than pooling every such hit into one.
            String key = chunk.entityId() != null ? "entity:" + chunk.entityId() : "chunk:" + chunk.chunkId();
            byEntity.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        List<SearchHit> results = new ArrayList<>(byEntity.size());
        for (List<SearchHit> members : byEntity.values()) {
            // Stable, so equal scores keep retrieval's order.
            members.sort(Comparator.comparingDouble(SearchHit::score).reversed());
            SearchHit best = members.get(0);
            int kept = cap <= 0 ? members.size() : Math.min(cap, members.size());

            List<SearchHit.Match> more = new ArrayList<>(Math.max(kept - 1, 0));
            double breadth = 0;
            for (SearchHit member : members.subList(1, kept)) {
                more.add(new SearchHit.Match(member.chunkId(), member.ordinal(), member.text(),
                        member.snippet(), member.score()));
                breadth += member.score();
            }
            double grouped = best.score() + extraMatchWeight * breadth;
            results.add(best.withMoreMatches(grouped, more)
                    .withRanking(best.ranking().withGroupedScore(grouped)));
        }
        results.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return List.copyOf(results);
    }
}
