package io.personalassistant.ingestion.connector.ats.zwayam;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import io.personalassistant.ingestion.connector.ats.CareersHost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zwayam career sites, addressed by the company's careers domain ({@code careers.cult.fit}). Search pages ten
 * hits at a time and carries no description, so a board of N jobs costs N/10 + N + 1 requests; the filter
 * runs on the hits first, and a failed detail fetch skips that job rather than failing the board.
 */
@ApplicationScoped
public class ZwayamPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(ZwayamPlatform.class.getName());

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** Fixed by the site, not by the request. */
    private static final int PAGE_SIZE = 10;

    /** Well above the largest board seen. */
    private static final int MAX_PAGES = 300;

    private final ZwayamApi api;

    @Inject
    public ZwayamPlatform(ZwayamApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "zwayam";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        Optional<String> domain = CareersHost.parse(handle);
        if (domain.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int total = api.search(domain.get(), 0).path("data").path("totalCount").asInt(0);
            return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        String domain = CareersHost.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Zwayam careers site: '" + handle
                        + "'. Expected the careers domain (e.g. careers.cult.fit) or a job URL on it."));
        List<JsonNode> hits = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = api.search(domain, page * PAGE_SIZE).path("data");
            JsonNode pageHits = data.path("data");
            if (!pageHits.isArray() || pageHits.isEmpty()) {
                break;
            }
            pageHits.forEach(hit -> hits.add(hit.path("_source")));
            if (hits.size() >= data.path("totalCount").asInt(hits.size())
                    || !data.path("hasMoreData").asBoolean(true)) {
                break;
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        Site site = site(domain, text(hits.get(0), "companyId"));
        List<RawItem> items = new ArrayList<>();
        for (JsonNode hit : hits) {
            // Filtered before the detail call; title first, the cheaper and sharper test.
            if (!filter.matchesTitle(text(hit, "jobTitle"))
                    || !AtsNormalization.matchesLocation(location(hit), filter.locations())) {
                continue;
            }
            RawItem item = toItem(site, company, hit);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** @param folder the path segment job pages live under; null leaves the link at the site's home page */
    private record Site(String domain, String companyId, String folder, String name) {

        String jobUrl(String jobUrl, String id) {
            return folder == null
                    ? "https://" + domain + "/"
                    : "https://" + domain + "/" + folder + "/jobview/" + jobUrl + "?id=" + id;
        }
    }

    private Site site(String domain, String companyId) {
        try {
            JsonNode company = api.careerSite(companyId).path("reponseObject").path("company");
            return new Site(domain, companyId, text(company, "folder"), text(company, "companyName"));
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Only the job links and the company name depend on it.
            LOG.log(Level.FINE, "Could not read the Zwayam site configuration for " + domain, e);
            return new Site(domain, companyId, null, null);
        }
    }

    private RawItem toItem(Site site, String label, JsonNode hit) {
        String id = text(hit, "id");
        String title = text(hit, "jobTitle");
        String jobUrl = text(hit, "jobUrl");
        String companyId = firstNonBlank(text(hit, "companyId"), site.companyId());
        if (id == null || title == null || jobUrl == null || companyId == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.job(companyId, jobUrl);
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Withdrawn since the search, or transient: skipping one job beats failing the board.
            LOG.log(Level.FINE, "Could not fetch Zwayam job " + id, e);
            return null;
        }
        if (detail == null) {
            return null;
        }

        String uri = site.jobUrl(jobUrl, id);
        String location = location(hit);
        // The site's own name outranks the label, which only replaces the bare domain.
        String companyName = firstNonBlank(site.name(), AtsNormalization.company(label, site.domain()));
        // The role first, so the opening chunk is about the job and not the company blurb.
        String content = detail.path("role").asText("") + detail.path("longDescription").asText("");
        Long created = millis(hit, "createdDate");
        Long modified = millis(hit, "modifiedDate");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", site.domain());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", text(detail, "departmentName"));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(created));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("domain", site.domain());
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                AtsNormalization.withCompany(
                        "zwayam:" + id + ";mod:" + (modified == null ? "" : modified)
                                + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, firstNonBlank(site.name(), site.domain())),
                AtsNormalization.instantOrNull(modified),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /** Multi-site jobs are slash separated ({@code Pune/Mumbai}). */
    private static String location(JsonNode hit) {
        return firstNonBlank(text(hit, "locationSeparatedbySlash"), text(hit, "location"));
    }

    private static Long millis(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return value.isTextual() ? Long.parseLong(value.asText().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
