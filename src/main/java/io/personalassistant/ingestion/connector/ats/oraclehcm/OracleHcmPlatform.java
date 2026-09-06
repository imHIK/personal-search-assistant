package io.personalassistant.ingestion.connector.ats.oraclehcm;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Oracle Recruiting Cloud career sites — the cluster that Workday's absence used to hide.
 *
 * <p>Probing a real 126-company watchlist, the employers that reached no board split into "runs its
 * own careers stack" and "runs a hosted ATS nothing here can read". The second group turned out to be
 * dominated by this one platform: BNY Mellon, JPMorgan Chase and Kotak Mahindra confirmed, with Akamai
 * and American Express on the same UI behind vanity domains. One bean reaches all of them, which is
 * why this was worth building before a per-employer connector for Amazon or Microsoft.
 *
 * <p>It is shaped like {@link io.personalassistant.ingestion.connector.ats.workday.WorkdayPlatform}
 * in the two ways that matter:
 *
 * <ul>
 *   <li><strong>It cannot resolve a bare company name.</strong> A site is a {@code host/siteNumber}
 *       pair and neither part is derivable from the company — see {@link OracleHcmSite}. So
 *       {@link #countPostings} answers empty <em>without a network call</em> for anything that does not
 *       parse, keeping resolution of ordinary names fast.</li>
 *   <li><strong>The location hint is a query, never a filter.</strong> Oracle's {@code keyword} finder
 *       runs server-side over the whole record; BNY's site answers 1,386 requisitions blank and 138 for
 *       {@code "Pune"}. One request per term, unioned, because the terms are alternative spellings.</li>
 * </ul>
 *
 * <p>And like SmartRecruiters, it pays <strong>per posting</strong>: the listing carries no
 * description at all ({@code ShortDescriptionStr} is empty and the qualification fields are null), so
 * every requisition kept costs a second call. That makes the keyword prefilter load-bearing rather
 * than merely nice — without it, JPMorgan's 7,325 requisitions would be 7,326 requests per poll.
 *
 * <p>The query stays a prefilter, not the authority: {@code keyword} matches description text too, so
 * the connector's own {@code matchesLocation} still runs over the result.
 */
@ApplicationScoped
public class OracleHcmPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(OracleHcmPlatform.class.getName());

    /** A direct board outranks any aggregator, matching the other platforms. */
    private static final int SOURCE_RANK = 100;

    /** The API serves large pages happily; 100 keeps a single response a sane size. */
    private static final int PAGE_SIZE = 100;

    /** Ceiling on pages walked per query, so a pathological site cannot spin forever. */
    private static final int MAX_PAGES = 100;

    private final OracleHcmApi api;

    @Inject
    public OracleHcmPlatform(OracleHcmApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "oraclehcm";
    }

    /**
     * {@inheritDoc}
     *
     * <p>The count lives at {@code items[0].TotalJobsCount}; a site that exists but is empty answers
     * zero, which is indistinguishable from a wrong site number and is reported as "no board" either
     * way — the same accepted ambiguity every platform here has.
     */
    @Override
    public OptionalInt countPostings(String handle) {
        Optional<OracleHcmSite> site = OracleHcmSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode response = api.searchRequisitions(site.get(), "", 1, 0);
            int total = total(response);
            return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A wrong host or site number answers with HTML or a 400 rather than a clean 404.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, BoardFilter filter) {
        OracleHcmSite site = OracleHcmSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not an Oracle HCM site: '" + handle
                        + "'. Expected host/siteNumber (e.g."
                        + " eofe.fa.us2.oraclecloud.com/BNY-Careers) or the career-site URL."));

        List<String> queries = filter.locations().isEmpty()
                ? List.of("")
                : List.copyOf(new LinkedHashSet<>(filter.locations()));

        // Keyed by requisition id: a posting matched by two terms must cost only one detail call.
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

    /** Walk one query's pages, adding each requisition to {@code into} under its id. */
    private void collectSummaries(OracleHcmSite site, String query, BoardFilter filter,
                                  Map<String, JsonNode> into) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.searchRequisitions(site, query, PAGE_SIZE, offset);
            JsonNode list = requisitions(response);
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode summary : list) {
                String id = summary.path("Id").asText(null);
                // Title tested on the listing: this platform's listing carries no description at all,
                // so every posting kept costs a second request and every one dropped here is saved.
                if (id != null && filter.matchesTitle(summary.path("Title").asText(null))) {
                    into.putIfAbsent(id, summary);
                }
            }
            offset += list.size();
            if (offset >= total(response)) {
                break;
            }
        }
    }

    /** Fetch a requisition in full and map it; null when it cannot be read or is unusable. */
    private RawItem toItem(OracleHcmSite site, JsonNode summary) {
        String id = summary.path("Id").asText(null);
        String title = summary.path("Title").asText(null);
        if (id == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = first(api.requisition(site, id));
        } catch (RuntimeException e) {
            // Withdrawn between the listing and this call, or a transient failure. Skipping one
            // posting beats failing the site and re-fetching every other detail.
            LOG.log(Level.FINE, "Could not fetch Oracle HCM requisition " + id, e);
            return null;
        }
        if (detail == null) {
            return null;
        }

        String location = location(summary, detail);
        String url = site.jobUrl(id);
        String content = sections(detail);
        String descriptionText = AtsNormalization.plainText(content);
        String posted = text(detail.path("ExternalPostedStartDate"),
                summary.path("PostedDate").asText(""));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", url);
        metadata.put("company", site.site());
        metadata.put("location", location);
        metadata.put("applyUrl", url);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred, as on SmartRecruiters: Oracle publishes a workplace type code.
        metadata.put("remote", "ORA_REMOTE".equals(detail.path("WorkplaceTypeCode").asText(""))
                || AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(detail.path("Category")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(site.site(), title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(posted));
        AtsNormalization.CompRange comp = AtsNormalization.compRange(descriptionText);
        if (comp != null) {
            metadata.put("compMin", comp.min());
            metadata.put("compMax", comp.max());
            putIfPresent(metadata, "compCurrency", comp.currency());
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("requisitionId", detail.path("RequisitionId").asText(""));
        raw.put("site", site.toString());
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                url,
                // ExternalPostedStartDate is when the requisition went live and does not move on an
                // edit, so the body is hashed as well — otherwise an edited posting is skipped forever
                // (invariant 3).
                "ohcm:" + id + ";posted:" + posted + ";body:" + content.hashCode(),
                AtsNormalization.instantOrNull(posted),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the knowledge-level retention window governs.
                null,
                false);
    }

    /**
     * The job-ad sections, concatenated as HTML in a fixed order so the same requisition always
     * produces the same body — the checksum hashes it. Kept as HTML because the parser strips it at
     * index time and headings survive into structure-aware chunking.
     */
    private static String sections(JsonNode detail) {
        StringBuilder out = new StringBuilder();
        for (String field : List.of("ExternalDescriptionStr", "ExternalResponsibilitiesStr",
                "ExternalQualificationsStr", "CorporateDescriptionStr")) {
            String text = detail.path(field).asText("");
            if (!text.isBlank()) {
                out.append(text);
            }
        }
        return out.toString();
    }

    /**
     * The human-readable location, preferring the detail's.
     *
     * <p>Oracle spells the country out — "Bengaluru, Karnataka, India" — so unlike Workday nothing has
     * to be appended for a term list naming "India" to match.
     */
    private static String location(JsonNode summary, JsonNode detail) {
        String primary = firstNonBlank(detail.path("PrimaryLocation").asText(null),
                summary.path("PrimaryLocation").asText(null));
        return primary == null ? "" : primary;
    }

    /** {@code items[0].requisitionList} — the search envelope nests one level deeper than most. */
    private static JsonNode requisitions(JsonNode response) {
        return first(response) == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : first(response).path("requisitionList");
    }

    private static int total(JsonNode response) {
        JsonNode item = first(response);
        return item == null ? 0 : item.path("TotalJobsCount").asInt(0);
    }

    private static JsonNode first(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode items = response.path("items");
        return items.isArray() && !items.isEmpty() ? items.get(0) : null;
    }

    private static String text(JsonNode node, String fallback) {
        String value = node == null ? null : node.asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static String nullableText(JsonNode node) {
        String value = node == null ? null : node.asText(null);
        return value == null || value.isBlank() || "None".equals(value) ? null : value;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
