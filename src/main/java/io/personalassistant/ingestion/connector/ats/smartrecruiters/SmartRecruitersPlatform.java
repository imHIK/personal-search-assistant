package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The listing carries metadata only, so a board of N postings costs 1 + N requests. The filter runs on the
 * listing first, and a failed detail fetch skips that posting rather than failing the board.
 */
@ApplicationScoped
public class SmartRecruitersPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(SmartRecruitersPlatform.class.getName());

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** The API caps a page at 100. */
    private static final int PAGE_SIZE = 100;

    /** Well above the largest board seen. */
    private static final int MAX_PAGES = 50;

    private final SmartRecruitersApi api;

    @Inject
    public SmartRecruitersPlatform(SmartRecruitersApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "smartrecruiters";
    }

    /** An unknown company returns 200 with {@code totalFound: 0}, not a 404. */
    @Override
    public OptionalInt countPostings(String handle) {
        try {
            JsonNode response = api.listPostings(handle, 1, 0);
            int total = response == null ? 0 : response.path("totalFound").asInt(0);
            return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        List<RawItem> items = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.listPostings(handle, PAGE_SIZE, offset);
            JsonNode content = response.path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            for (JsonNode summary : content) {
                // Filtered before the detail call; title first, the cheaper and sharper test.
                if (!filter.matchesTitle(summary.path("name").asText(null))
                        || !AtsNormalization.matchesLocation(location(summary), filter.locations())) {
                    continue;
                }
                RawItem item = toItem(handle, company, summary);
                if (item != null) {
                    items.add(item);
                }
            }
            offset += content.size();
            if (offset >= response.path("totalFound").asInt(offset)) {
                break;
            }
        }
        return items;
    }

    private RawItem toItem(String company, String label, JsonNode summary) {
        String id = summary.path("id").asText(null);
        String title = summary.path("name").asText(null);
        if (id == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.posting(company, id);
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Withdrawn since the listing, or transient: skipping one posting beats failing the board.
            LOG.log(Level.FINE, "Could not fetch SmartRecruiters posting " + id, e);
            return null;
        }
        if (detail == null) {
            return null;
        }

        String location = location(summary);
        String applyUrl = firstNonBlank(detail.path("applyUrl").asText(null),
                detail.path("postingUrl").asText(null));
        // The board's own name outranks the label, which only replaces the bare handle.
        String stated = detail.path("company").path("name").asText(null);
        String companyName = firstNonBlank(stated, AtsNormalization.company(label, company));
        String content = sections(detail);
        String descriptionText = AtsNormalization.plainText(content);
        String releasedDate = summary.path("releasedDate").asText("");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", company);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: remote is a real boolean here.
        metadata.put("remote", summary.path("location").path("remote").asBoolean(false)
                || AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(detail.path("department").path("label")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(releasedDate));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("company", company);
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                applyUrl,
                // releasedDate does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "sr:" + id + ";rel:" + releasedDate + ";body:" + content.hashCode(),
                        companyName, firstNonBlank(stated, company)),
                AtsNormalization.instantOrNull(releasedDate),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /** Kept as HTML: the parser strips it at index time, and headings survive into chunking. */
    private static String sections(JsonNode detail) {
        JsonNode sections = detail.path("jobAd").path("sections");
        StringBuilder out = new StringBuilder();
        // Fixed order, so the checksum over the body is stable.
        for (String name : List.of("jobDescription", "qualifications", "additionalInformation",
                "companyDescription")) {
            JsonNode section = sections.path(name);
            String text = section.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            String heading = section.path("title").asText("");
            if (!heading.isBlank()) {
                out.append("<h2>").append(heading).append("</h2>");
            }
            out.append(text);
        }
        return out.toString();
    }

    /**
     * fullLocation spells the country out; the structured fields give only an ISO code that a term like India
     * never matches.
     */
    private static String location(JsonNode summary) {
        JsonNode location = summary.path("location");
        String full = location.path("fullLocation").asText("");
        if (!full.isBlank()) {
            return full;
        }
        String city = location.path("city").asText("");
        String region = location.path("region").asText("");
        String joined = (city + ", " + region).trim();
        return joined.equals(",") ? "" : joined.replaceAll("^,\\s*|,\\s*$", "");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static String nullableText(JsonNode node) {
        String value = node == null ? null : node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
