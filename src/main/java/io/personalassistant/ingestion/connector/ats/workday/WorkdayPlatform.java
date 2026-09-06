package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Workday career sites — the largest pool of India roles of any supported platform, and the most
 * awkward to address.
 *
 * <p>Three things make it different from every other platform here:
 *
 * <ul>
 *   <li><strong>It cannot resolve a bare company name.</strong> A site is a {@code tenant/site/wd}
 *       triple and none of the three parts is guessable — 13 of 22 blind attempts failed on companies
 *       that certainly use Workday. {@link #countPostings} therefore answers empty without any
 *       network call for anything that is not a triple or a pasted career-site URL, which also keeps
 *       it from slowing down resolution of every ordinary company name.</li>
 *   <li><strong>Search is a POST</strong>, with the paging window in the body, and pages at 20 against
 *       tenants holding several hundred postings.</li>
 *   <li><strong>The location hint is a query, never a filter.</strong> Workday's search result is
 *       systematically less complete than its detail: it gives a bare city ({@code "Bengaluru"}) with
 *       no country, and for a multi-site role a <em>count</em> ({@code "5 Locations"}) with no place at
 *       all. <em>Filtering</em> on that would drop a Bengaluru role whenever the filter names "India",
 *       and drop every multi-site role outright. But {@code searchText} runs against Workday's own
 *       index, which reads the whole record — so asking narrows the answer without losing the roles a
 *       filter would have lost. One request per term, unioned, because Workday reads a multi-word
 *       query conjunctively and the terms are alternatives.</li>
 * </ul>
 *
 * <p>That last point is what stopped this being the most expensive platform here. Fetching a whole
 * site costs {@code 1 + N} requests — Lowe's is 12,424 postings — where SmartRecruiters pays only for
 * what survives the hint. Querying brings Workday to the same footing: Lowe's answers 48 postings for
 * {@code "Bengaluru"}, so a located source costs ~50 requests rather than ~12,400. A site added with
 * <strong>no</strong> locations still walks the whole board, and for a large tenant that is minutes
 * per poll — set locations, or a slow schedule, or both.
 *
 * <p>The query is a prefilter and not the authority: {@code searchText} matches description text too,
 * so the connector's own {@code matchesLocation} still runs over the result and remains the real
 * filter. What the location list bounds beyond the fetch is unchanged — entities, chunks and
 * embeddings.
 */
@ApplicationScoped
public class WorkdayPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(WorkdayPlatform.class.getName());

    private static final int SOURCE_RANK = 100;

    /** Workday caps a search page at 20 regardless of the limit asked for. */
    private static final int PAGE_SIZE = 20;

    /** Ceiling on pages walked: 100 pages is 2,000 postings, well past the largest site seen. */
    private static final int MAX_PAGES = 100;

    private final WorkdayApi api;

    @Inject
    public WorkdayPlatform(WorkdayApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "workday";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code false} <strong>without a network call</strong> unless the handle parses as a
     * site triple or career-site URL. Resolution probes every platform for every company, so a bare
     * name like {@code "paytm"} must not cost a speculative request here — there is no tenant to guess.
     */
    @Override
    public OptionalInt countPostings(String handle) {
        Optional<WorkdaySite> site = WorkdaySite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode response = api.searchJobs(site.get(), 1, 0, "");
            if (response == null || !response.hasNonNull("total")) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(response.path("total").asInt(0));
        } catch (RuntimeException e) {
            // A wrong site slug or pod answers with HTML or a 422 rather than a clean 404.
            return OptionalInt.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code locationHints} are sent to Workday as <strong>queries</strong>, not applied to the
     * results. The distinction is the whole design here, and it is not the same trade-off the class
     * javadoc rejects: a returned summary carries a bare city or {@code "5 Locations"}, so filtering
     * <em>results</em> silently drops multi-site roles — but {@code searchText} runs against Workday's
     * own index, which sees the full record, so a query <em>finds</em> that same role. Measured on
     * Lowe's: 12,424 postings for a blank query, 48 for {@code "Bengaluru"}.
     *
     * <p>One request per term, unioned, rather than one query joining them: the terms are alternatives
     * ("bengaluru" OR "bangalore") and Workday reads a multi-word query conjunctively, so joining them
     * returns almost nothing. On Lowe's the two spellings answer 48 and 3 postings and overlap not at
     * all — a single combined query would have lost both.
     *
     * <p>The prefilter is cheap, not authoritative: {@code searchText} matches description text too, so
     * it returns roles that are not in the named place at all. The connector's own
     * {@link AtsNormalization#matchesLocation} still runs afterwards and remains the real filter.
     */
    @Override
    public List<RawItem> fetch(String handle, BoardFilter filter) {
        WorkdaySite site = WorkdaySite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Workday site: '" + handle
                        + "'. Expected tenant/site/wd (e.g. adobe/external_experienced/wd5) or the"
                        + " career-site URL."));

        // No hints means no way to narrow, so the whole site is walked exactly as before.
        List<String> queries = filter.locations().isEmpty()
                ? List.of("")
                : List.copyOf(new LinkedHashSet<>(filter.locations()));

        // Keyed by externalPath: the same posting is returned by every term that matches it, and the
        // detail call is the expensive part, so de-duplicate before mapping rather than after.
        Map<String, JsonNode> summaries = new LinkedHashMap<>();
        for (String query : queries) {
            collectSummaries(site, query, filter, summaries);
        }

        List<RawItem> items = new ArrayList<>(summaries.size());
        for (JsonNode summary : summaries.values()) {
            RawItem item = toItem(site, summary);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** Walk one query's pages, adding each posting to {@code into} under its externalPath. */
    private void collectSummaries(WorkdaySite site, String query, BoardFilter filter,
                                  Map<String, JsonNode> into) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.searchJobs(site, PAGE_SIZE, offset, query);
            JsonNode postings = response.path("jobPostings");
            if (!postings.isArray() || postings.isEmpty()) {
                break;
            }
            for (JsonNode summary : postings) {
                String path = summary.path("externalPath").asText(null);
                // The title test runs here, on the summary, so a rejected posting never costs the
                // detail call that dominates this platform's request count.
                if (path != null && filter.matchesTitle(summary.path("title").asText(null))) {
                    into.putIfAbsent(path, summary);
                }
            }
            offset += postings.size();
            if (offset >= response.path("total").asInt(offset)) {
                break;
            }
        }
    }

    private RawItem toItem(WorkdaySite site, JsonNode summary) {
        String path = summary.path("externalPath").asText(null);
        String title = summary.path("title").asText(null);
        if (path == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.posting(site, path).path("jobPostingInfo");
        } catch (RuntimeException e) {
            // Filled or withdrawn between the search and the fetch. Skipping one posting beats
            // failing the site and re-fetching every other detail.
            LOG.log(Level.FINE, "Could not fetch Workday posting " + path, e);
            return null;
        }
        if (detail.isMissingNode() || detail.isNull()) {
            return null;
        }

        String id = firstNonBlank(detail.path("jobReqId").asText(null), path);
        String location = locationOf(detail);
        String applyUrl = detail.path("externalUrl").asText(null);
        String content = detail.path("jobDescription").asText("");
        String descriptionText = AtsNormalization.plainText(content);
        String startDate = detail.path("startDate").asText("");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", site.tenant());
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(site.tenant(), title, location));
        // postedOn is relative prose ("Posted Today"), so startDate is the only usable date.
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(startDate + "T00:00:00Z"));
        AtsNormalization.CompRange comp = AtsNormalization.compRange(descriptionText);
        if (comp != null) {
            metadata.put("compMin", comp.min());
            metadata.put("compMax", comp.max());
            putIfPresent(metadata, "compCurrency", comp.currency());
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("site", site.toString());
        raw.put("externalPath", path);
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                applyUrl,
                // No update timestamp is published — postedOn is prose and startDate is the requisition
                // date, neither of which moves on an edit. Hashing the body is the only change signal
                // available, as with Lever and SmartRecruiters (invariant 3).
                "wd:" + id + ";start:" + startDate + ";body:" + content.hashCode(),
                AtsNormalization.instantOrNull(startDate + "T00:00:00Z"),
                raw,
                content,
                null,
                metadata,
                null,
                false);
    }

    /**
     * Every location a posting names, plus its country.
     *
     * <p>The country is appended because the location fields hold city names alone — a filter naming
     * "India" would otherwise never match a role in Bengaluru, which is the single most common way this
     * kind of filter silently loses roles.
     */
    private static String locationOf(JsonNode detail) {
        Set<String> parts = new LinkedHashSet<>();
        addIfPresent(parts, detail.path("location").asText(""));
        for (JsonNode extra : detail.path("additionalLocations")) {
            addIfPresent(parts, extra.asText(""));
        }
        String country = detail.path("country").path("descriptor").asText("");
        String joined = String.join("; ", parts);
        if (country.isBlank()) {
            return joined;
        }
        return joined.isBlank() ? country : joined + ", " + country;
    }

    private static void addIfPresent(Set<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.trim());
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
