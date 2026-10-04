package io.personalassistant.ingestion.connector.ats.jibe;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * iCIMS customers' branded careers sites, which run on Jibe and serve the whole board as JSON. A site is a
 * host, never derivable from a name, so countPostings answers empty without a network call for anything that
 * does not parse as one.
 */
@ApplicationScoped
public class JibePlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** The API caps a page at 100. */
    private static final int PAGE_SIZE = 100;

    /** Well above the largest board seen. */
    private static final int MAX_PAGES = 50;

    /** Jibe writes {@code +0000}, which neither ISO parser accepts. */
    private static final Pattern COMPACT_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");

    private final JibeApi api;

    @Inject
    public JibePlatform(JibeApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "jibe";
    }

    /** A host that is not a Jibe site answers HTML or JSON without a total: both read as no board. */
    @Override
    public OptionalInt countPostings(String handle) {
        Optional<JibeSite> site = JibeSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode response = api.listJobs(site.get(), 1, 1);
            int total = response == null || !response.path("jobs").isArray()
                    ? 0 : response.path("totalCount").asInt(0);
            return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        JibeSite site = JibeSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Jibe careers site: '" + handle
                        + "'. Expected the careers host (e.g. careers.docusign.com) or a job URL on it."));
        // Hint ignored: the listing is the whole posting, so filtering early saves no request.
        List<RawItem> items = new ArrayList<>();
        int seen = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode response = api.listJobs(site, page, PAGE_SIZE);
            JsonNode jobs = response.path("jobs");
            if (!jobs.isArray() || jobs.isEmpty()) {
                break;
            }
            for (JsonNode job : jobs) {
                RawItem item = toItem(site, company, job.path("data"));
                if (item != null) {
                    items.add(item);
                }
            }
            seen += jobs.size();
            if (seen >= response.path("totalCount").asInt(seen)) {
                break;
            }
        }
        return items;
    }

    private RawItem toItem(JibeSite site, String label, JsonNode job) {
        String id = firstNonBlank(text(job, "req_id"), text(job, "slug"));
        String title = text(job, "title");
        if (id == null || title == null) {
            return null;
        }
        String slug = firstNonBlank(text(job, "slug"), id);
        String uri = site.jobUrl(slug);
        String applyUrl = firstNonBlank(text(job, "apply_url"), uri);
        String location = firstNonBlank(text(job, "full_location"), text(job, "location_name"));
        // location_name is the requisition's own label and is where "Remote" is stated.
        String locationName = text(job, "location_name");
        String hiring = text(job, "hiring_organization");
        String companyName = firstNonBlank(hiring, AtsNormalization.company(label, site.host()));
        String content = sections(job);
        String updated = firstNonBlank(text(job, "update_date"), text(job, "posted_date"));
        Instant postedAt = instant(text(job, "posted_date"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", site.host());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(
                (locationName == null ? "" : locationName) + " " + (location == null ? "" : location),
                AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", firstCategory(job));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", postedAt);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("host", site.host());
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                // update_date moves on an edit; the body is hashed too, in case a site does not bump it.
                AtsNormalization.withCompany(
                        "jibe:" + id + ";upd:" + (updated == null ? "" : updated)
                                + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, firstNonBlank(hiring, site.host())),
                instant(updated),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /** Kept as HTML: the parser strips it at index time, and headings survive into chunking. */
    private static String sections(JsonNode job) {
        StringBuilder out = new StringBuilder(job.path("description").asText(""));
        // Fixed order, so the checksum over the body is stable.
        for (String name : List.of("responsibilities", "qualifications")) {
            String text = job.path(name).asText("");
            if (!text.isBlank()) {
                out.append("<h2>").append(Character.toUpperCase(name.charAt(0))).append(name.substring(1))
                        .append("</h2>").append(text);
            }
        }
        return out.toString();
    }

    private static String firstCategory(JsonNode job) {
        JsonNode categories = job.path("categories");
        if (categories.isArray() && !categories.isEmpty()) {
            String name = categories.get(0).path("name").asText("").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    static Instant instant(String value) {
        return value == null ? null
                : AtsNormalization.instantOrNull(COMPACT_OFFSET.matcher(value).replaceFirst("$1:$2"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
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
