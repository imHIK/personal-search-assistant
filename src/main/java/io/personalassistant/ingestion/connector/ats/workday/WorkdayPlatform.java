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
 * Workday career sites. A site is a tenant/site/wd triple, none of it guessable, so countPostings answers
 * empty without a network call for anything that is not one. The location terms go out as searchText queries,
 * one per term and unioned since Workday reads a multi-word query conjunctively, and never filter the
 * summaries: those give a bare city or "5 Locations" and would silently drop roles. A site with no locations
 * walks the whole board, which takes minutes per poll on a large tenant.
 */
@ApplicationScoped
public class WorkdayPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(WorkdayPlatform.class.getName());

    private static final int SOURCE_RANK = 100;

    /** Workday caps a search page at 20. */
    private static final int PAGE_SIZE = 20;

    /** Well past the largest site seen. */
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
     * No network call unless the handle parses as a triple or URL: resolution probes every platform for every
     * name.
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
            // A wrong site slug or pod answers with HTML or a 422, not a 404.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        WorkdaySite site = WorkdaySite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Workday site: '" + handle
                        + "'. Expected tenant/site/wd (e.g. adobe/external_experienced/wd5) or the"
                        + " career-site URL."));

        // No terms: the whole site is walked.
        List<String> queries = filter.locations().isEmpty()
                ? List.of("")
                : List.copyOf(new LinkedHashSet<>(filter.locations()));

        // Keyed by externalPath: every matching term returns the posting, and the detail call is the
        // expensive part.
        Map<String, JsonNode> summaries = new LinkedHashMap<>();
        for (String query : queries) {
            collectSummaries(site, query, filter, summaries);
        }

        List<RawItem> items = new ArrayList<>(summaries.size());
        for (JsonNode summary : summaries.values()) {
            RawItem item = toItem(site, company, summary);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

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
                // The title test runs on the summary, saving the detail call.
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

    private RawItem toItem(WorkdaySite site, String label, JsonNode summary) {
        String path = summary.path("externalPath").asText(null);
        String title = summary.path("title").asText(null);
        if (path == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.posting(site, path).path("jobPostingInfo");
        } catch (RuntimeException e) {
            // Filled or withdrawn since the search: skipping one posting beats failing the site.
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
        // Not hiringOrganization.name: that is a legal entity with a tax id in front of it.
        String company = AtsNormalization.company(label, site.tenant());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", company);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        // postedOn is relative prose ("Posted Today"), so startDate is the only usable date.
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(startDate + "T00:00:00Z"));

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
                // Neither date moves on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "wd:" + id + ";start:" + startDate + ";body:" + content.hashCode(),
                        company, site.tenant()),
                AtsNormalization.instantOrNull(startDate + "T00:00:00Z"),
                raw,
                content,
                null,
                metadata,
                null,
                false);
    }

    /**
     * The country is appended: the location fields hold city names alone, and a term like India would
     * otherwise never match.
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
