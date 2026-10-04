package io.personalassistant.retrieval;

import io.personalassistant.domain.model.search.SearchHit;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Groups results that are the same material by different routes and keeps one per group; nothing is deleted.
 * Layers, cheapest first: identical normalised text, an equal {@code metadata.dedupeKey}, then high shingle
 * overlap. The text layers also require alike titles, since identical text under unrelated titles is shared
 * boilerplate. Shingles rather than embedding cosine: distinct roles at one company sit close in embedding
 * space, and hiding a real result is worse than showing a duplicate.
 */
@ApplicationScoped
public class DuplicateCollapser {

    /** Mirrors the config default, for the constructor that predates the title check. */
    private static final double DEFAULT_TITLE_SIMILARITY = 0.5;

    /**
     * Long enough that shared phrasing between different documents does not register, short enough to survive
     * light editing.
     */
    @ConfigProperty(name = "app.search.dedupe.shingle-size", defaultValue = "5")
    int shingleSize;

    /**
     * High on purpose: a false merge silently hides a result, while a missed one is a visible duplicate. Not
     * higher, because overlap is length-sensitive: a short posting behind a three-word banner already lands
     * near 0.89.
     */
    @ConfigProperty(name = "app.search.dedupe.near-duplicate-threshold", defaultValue = "0.85")
    double nearDuplicateThreshold;

    /** The near-duplicate pass is O(n²); hits beyond this still go through the exact layers. */
    @ConfigProperty(name = "app.search.dedupe.max-comparisons", defaultValue = "100")
    int maxComparisons;

    /**
     * 0.5 still groups "Fwd: Q3 report" with "Q3 report" while keeping "Backend Engineer" and "Frontend
     * Engineer" apart. A blank title does not block.
     */
    @ConfigProperty(name = "app.search.dedupe.title-similarity", defaultValue = "0.5")
    double titleSimilarity;

    public DuplicateCollapser() {
    }

    public DuplicateCollapser(int shingleSize, double nearDuplicateThreshold, int maxComparisons) {
        this(shingleSize, nearDuplicateThreshold, maxComparisons, DEFAULT_TITLE_SIMILARITY);
    }

    public DuplicateCollapser(int shingleSize, double nearDuplicateThreshold, int maxComparisons,
                              double titleSimilarity) {
        this.shingleSize = shingleSize;
        this.nearDuplicateThreshold = nearDuplicateThreshold;
        this.maxComparisons = maxComparisons;
        this.titleSimilarity = titleSimilarity;
    }

    /**
     * A group sits at its best-ranked member's position, so nothing is promoted. The member kept is the one
     * with the highest {@code metadata.sourceRank} (a company's own board over an aggregator), else the
     * best-ranked.
     */
    public List<SearchHit> collapse(List<SearchHit> hits) {
        if (hits == null || hits.size() < 2) {
            return hits == null ? List.of() : hits;
        }
        Map<String, Integer> groupByExactText = new HashMap<>();
        Map<String, Integer> groupByKey = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        List<Set<String>> shingles = new ArrayList<>();

        for (SearchHit hit : hits) {
            String normalised = normalise(hit.groundingText());
            Set<String> title = tokens(hit.title());
            String dedupeKey = stringMetadata(hit, "dedupeKey");

            Integer target = normalised.isEmpty() ? null : groupByExactText.get(normalised);
            if (target != null && !titlesAgree(title, groups.get(target).title)) {
                target = null;
            }
            if (target == null && dedupeKey != null) {
                target = groupByKey.get(dedupeKey);
            }
            Set<String> hitShingles = shingle(normalised);
            if (target == null) {
                target = nearDuplicateOf(hitShingles, title, shingles, groups);
            }

            if (target == null) {
                groups.add(new Group(hit, title));
                shingles.add(hitShingles);
                target = groups.size() - 1;
            } else {
                groups.get(target).consider(hit);
                // A later member's shingles are not merged into the group's, which would chain A~B and B~C
                // into one group.
            }
            if (!normalised.isEmpty()) {
                groupByExactText.putIfAbsent(normalised, target);
            }
            if (dedupeKey != null) {
                groupByKey.putIfAbsent(dedupeKey, target);
            }
        }
        return groups.stream().map(Group::kept).toList();
    }

    private Integer nearDuplicateOf(Set<String> candidate, Set<String> title, List<Set<String>> shingles,
                                    List<Group> groups) {
        if (candidate.isEmpty()) {
            return null;
        }
        int compared = Math.min(shingles.size(), Math.max(maxComparisons, 0));
        for (int i = 0; i < compared; i++) {
            if (titlesAgree(title, groups.get(i).title)
                    && jaccard(candidate, shingles.get(i)) >= nearDuplicateThreshold) {
                return i;
            }
        }
        return null;
    }

    private boolean titlesAgree(Set<String> a, Set<String> b) {
        return a.isEmpty() || b.isEmpty() || jaccard(a, b) >= titleSimilarity;
    }

    private static final class Group {
        private final SearchHit best;   // earliest-ranked member; fixes the group's position
        private final Set<String> title; // the best member's title tokens, which later members must agree with
        private SearchHit kept;
        private int keptRank;

        Group(SearchHit first, Set<String> title) {
            this.best = first;
            this.title = title;
            this.kept = first;
            this.keptRank = sourceRank(first);
        }

        void consider(SearchHit candidate) {
            int rank = sourceRank(candidate);
            if (rank > keptRank) {
                kept = candidate;
                keptRank = rank;
            }
        }

        /**
         * Scored with the group's best rank, or preferring a lower-ranked member would drag the group down
         * the list.
         */
        SearchHit kept() {
            return kept == best ? best : kept.withScore(best.score());
        }

        private static int sourceRank(SearchHit hit) {
            Object value = hit.metadata() == null ? null : hit.metadata().get("sourceRank");
            return value instanceof Number n ? n.intValue() : 0;
        }
    }

    private static String stringMetadata(SearchHit hit, String key) {
        Object value = hit.metadata() == null ? null : hit.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Set<String> tokens(String text) {
        String normalised = normalise(text);
        return normalised.isEmpty() ? Set.of() : Set.copyOf(List.of(normalised.split(" ")));
    }

    private Set<String> shingle(String normalised) {
        if (normalised.isEmpty()) {
            return Set.of();
        }
        String[] tokens = normalised.split(" ");
        int width = Math.max(shingleSize, 1);
        if (tokens.length < width) {
            // Too short to shingle: the whole text is one shingle, so identical short texts still match.
            return Set.of(normalised);
        }
        Set<String> out = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + width <= tokens.length; i++) {
            sb.setLength(0);
            for (int j = 0; j < width; j++) {
                if (j > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i + j]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int intersection = 0;
        for (String s : smaller) {
            if (larger.contains(s)) {
                intersection++;
            }
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection / union.size();
    }
}
