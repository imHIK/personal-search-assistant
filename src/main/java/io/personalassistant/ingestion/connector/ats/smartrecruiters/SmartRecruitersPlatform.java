package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
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
 * SmartRecruiters job boards. Public and unauthenticated, like the other platforms — but the only one
 * that pays <em>per posting</em>, which shapes how it is driven.
 *
 * <p>Its listing carries metadata only; the description lives behind a second call per posting. So a
 * board of N postings costs 1 + N requests, where Greenhouse costs 1. Two things keep that bounded:
 *
 * <ul>
 *   <li><strong>The location hint is applied to the listing first.</strong> Only postings that survive
 *       are fetched in full. Freshworks is 157 postings of which 34 are in India, so filtering first
 *       turns 157 detail requests per poll into 34.</li>
 *   <li><strong>A failed detail fetch skips that posting</strong> rather than failing the board. One
 *       posting withdrawn between the listing and the fetch must not cost the other 156.</li>
 * </ul>
 *
 * <p>This platform reaches companies the others cannot: Swiggy (71 postings, 70 in India) and
 * Freshworks are on none of Greenhouse, Lever or Ashby.
 */
@ApplicationScoped
public class SmartRecruitersPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(SmartRecruitersPlatform.class.getName());

    /** A direct board outranks any aggregator, matching the other platforms. */
    private static final int SOURCE_RANK = 100;

    /** The API caps a page at 100 however large a limit is asked for. */
    private static final int PAGE_SIZE = 100;

    /**
     * Ceiling on pages walked, so a pathological board cannot spin forever. 50 pages is 5,000 postings
     * — an order of magnitude above the largest board seen.
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>An unknown company returns <strong>200 with {@code totalFound: 0}</strong>, not a 404, so
     * resolution has to read the field. Treating the status code as the answer would resolve every
     * company that has ever been typed to this platform.
     */
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
    public List<RawItem> fetch(String handle, List<String> locationHints) {
        List<RawItem> items = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.listPostings(handle, PAGE_SIZE, offset);
            JsonNode content = response.path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            for (JsonNode summary : content) {
                // Filtered BEFORE the detail call — that is the whole point of doing it here rather
                // than leaving it all to the connector.
                if (!AtsNormalization.matchesLocation(location(summary), locationHints)) {
                    continue;
                }
                RawItem item = toItem(handle, summary);
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

    /** Fetch a posting in full and map it; null when it cannot be read or is unusable. */
    private RawItem toItem(String company, JsonNode summary) {
        String id = summary.path("id").asText(null);
        String title = summary.path("name").asText(null);
        if (id == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.posting(company, id);
        } catch (RuntimeException e) {
            // Withdrawn between the listing and this call, or a transient failure. Skipping one
            // posting is far better than failing the board and re-fetching every other detail.
            LOG.log(Level.FINE, "Could not fetch SmartRecruiters posting " + id, e);
            return null;
        }
        if (detail == null) {
            return null;
        }

        String location = location(summary);
        String applyUrl = firstNonBlank(detail.path("applyUrl").asText(null),
                detail.path("postingUrl").asText(null));
        String companyName = firstNonBlank(detail.path("company").path("name").asText(null), company);
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
        // Stated beats inferred: SmartRecruiters publishes remote as a real boolean on the location.
        metadata.put("remote", summary.path("location").path("remote").asBoolean(false)
                || AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(detail.path("department").path("label")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(releasedDate));
        AtsNormalization.CompRange comp = AtsNormalization.compRange(descriptionText);
        if (comp != null) {
            metadata.put("compMin", comp.min());
            metadata.put("compMax", comp.max());
            putIfPresent(metadata, "compCurrency", comp.currency());
        }

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
                // Like Lever, SmartRecruiters exposes no update timestamp — releasedDate is when the
                // posting first went live and does not move on an edit. Hashing the body is the only
                // signal available, or an edited posting would be skipped forever (invariant 3).
                "sr:" + id + ";rel:" + releasedDate + ";body:" + content.hashCode(),
                AtsNormalization.instantOrNull(releasedDate),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the knowledge-level retention window governs.
                null,
                false);
    }

    /**
     * The four job-ad sections, concatenated with their headings as HTML.
     *
     * <p>Kept as HTML rather than flattened here: the parser at index time strips it, and keeping the
     * source form means headings survive into structure-aware chunking.
     */
    private static String sections(JsonNode detail) {
        JsonNode sections = detail.path("jobAd").path("sections");
        StringBuilder out = new StringBuilder();
        // Fixed order, so the same posting always produces the same body — the checksum hashes it.
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
     * The human-readable location. {@code fullLocation} is preferred because it spells the country out
     * — "Coimbatore, TN, India" — where the structured fields give only the ISO code {@code in}, which
     * a term list naming "India" would never match.
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
