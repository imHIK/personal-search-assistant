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
 * Groups results that are the same material arriving by different routes, and keeps one member of each
 * group. A general corpus problem, not a job-board one: the same file lives in Drive and as a mail
 * attachment, a thread is forwarded, a document is copied between folders — and the unique
 * {@code (knowledgeId, externalId)} index cannot see any of that, because it is scoped to a single
 * knowledge by construction.
 *
 * <p>Runs on grouped results (one per entity), comparing each result's whole grounding text — its best
 * chunk and further matches — rather than one chunk.
 *
 * <p>Three layers, cheapest first, each one a grouping rule rather than a deletion:
 *
 * <ol>
 *   <li><b>Exact</b> — identical normalised text. Catches literal copies across knowledges.</li>
 *   <li><b>Canonical key</b> — an equal {@code metadata.dedupeKey}. The framework only groups on the
 *       key; what it <em>means</em> is the source's business (the ATS normalisers build
 *       {@code company|title|location}).</li>
 *   <li><b>Near-duplicate</b> — high token-shingle overlap, for copies that differ by a header, a
 *       footer or light re-wording.</li>
 * </ol>
 *
 * <p>The two text layers additionally require the titles to be alike. Identical text under unrelated
 * titles is shared boilerplate, not a copy: every posting of one company carries the same "About us",
 * so text alone merged different roles whose best-matching passage happened to be the company intro.
 *
 * <h2>Why shingles rather than embedding cosine</h2>
 * Cosine over the chunk vectors would answer a different question. Two genuinely distinct roles at the
 * same company, or two separate reports on one project, sit very close in embedding space — collapsing
 * them would hide a real result, which is a far worse failure than showing a duplicate. Shingle overlap
 * measures shared wording, which is what "the same document twice" actually looks like. It is also free
 * here: the hits already carry their text, whereas the vectors are deliberately excluded from
 * {@code _source} and would have to be fetched back.
 *
 * <h2>Non-destructive, and opt-in</h2>
 * Nothing is deleted and no index is touched — this reorders a result list. It runs only when the caller
 * sets {@code collapseDuplicates}; the console sets it by default, API callers choose.
 */
@ApplicationScoped
public class DuplicateCollapser {

    /** Title agreement used by the test constructor that predates the title check; mirrors the config default. */
    private static final double DEFAULT_TITLE_SIMILARITY = 0.5;

    /**
     * Shingle width in tokens. Long enough that ordinary shared phrasing between two different documents
     * does not register, short enough to survive light editing of a genuine copy.
     */
    @ConfigProperty(name = "app.search.dedupe.shingle-size", defaultValue = "5")
    int shingleSize;

    /**
     * Jaccard overlap at or above which two hits are treated as the same material. Set high on purpose:
     * a false merge silently removes a result the user should have seen, and there is no way for them to
     * discover it, whereas a missed merge is merely a visible duplicate.
     *
     * <p>Not higher, though, because overlap is sensitive to length. A boilerplate header costs a fixed
     * number of shingles, so on a short chunk it moves the ratio a long way — an aggregator prefixing
     * three words to a 30-token posting already lands near 0.89. The floor is set below that so a genuine
     * copy with a banner still groups, while unrelated text (which shares almost no 5-token shingle at
     * all, scoring near zero) stays nowhere close.
     */
    @ConfigProperty(name = "app.search.dedupe.near-duplicate-threshold", defaultValue = "0.85")
    double nearDuplicateThreshold;

    /**
     * Cap on how many hits take part in the pairwise near-duplicate pass, which is O(n²) in the number of
     * candidates. Everything beyond this still goes through the two exact layers.
     */
    @ConfigProperty(name = "app.search.dedupe.max-comparisons", defaultValue = "100")
    int maxComparisons;

    /**
     * Token overlap two titles need before matching text may group their hits. 0.5 still groups a
     * forwarded or copied item ("Fwd: Q3 report" / "Q3 report", "Copy of Budget" / "Budget") while
     * keeping "Backend Engineer" and "Frontend Engineer" apart. A blank title has nothing to compare and
     * does not block. The key layer is unaffected: a source that states a {@code dedupeKey} has already
     * said what "the same" means.
     */
    @ConfigProperty(name = "app.search.dedupe.title-similarity", defaultValue = "0.5")
    double titleSimilarity;

    public DuplicateCollapser() {
    }

    /**
     * Test-friendly constructor that sets the tunables explicitly (CDI uses the no-arg one). The
     * {@code @ConfigProperty} fields are package-private per house style, which callers in another
     * package cannot reach.
     */
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
     * Collapse {@code hits}, preserving rank order.
     *
     * <p>A group is represented by its <em>best-ranked</em> member's position, so collapsing never
     * promotes anything above something it did not already outrank. Which member is kept is a separate
     * question, answered by {@code metadata.sourceRank}: a higher rank wins, so a posting found both on a
     * company's own board and through an aggregator keeps the canonical listing with the real apply URL.
     * With no rank stated anywhere, the best-ranked member is kept.
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
                // A later member's shingles are deliberately not merged into the group's: comparing
                // against a union would let a group drift, chaining A~B and B~C into one group even when
                // A and C share nothing.
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

    /** The index of the first group whose representative is a near-duplicate under an agreeing title, or null. */
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

    /** One cluster: where it sits in the ranking, and which member is worth showing. */
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
         * The kept member, but scored with the group's best rank — otherwise preferring a
         * lower-ranked member by {@code sourceRank} would also drag its position down the list.
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

    /** Lowercase and collapse everything non-alphanumeric, so formatting differences do not matter. */
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
            // Too short to shingle at the configured width; treat the whole thing as one shingle so two
            // identical short chunks still match and two different ones still do not.
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
