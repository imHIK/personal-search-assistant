package io.personalassistant.ingestion.connector.ats.oraclehcm;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Oracle Recruiting Cloud career sites. A site is a host/siteNumber pair, never derivable from a name, so
 * countPostings answers empty without a network call for anything that does not parse. Each location term is
 * resolved to the pod's place ids and queried by id, unioned; the connector still filters the result. Every requisition kept costs a detail call, so the query matters.
 */
@ApplicationScoped
public class OracleHcmPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(OracleHcmPlatform.class.getName());

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private static final int PAGE_SIZE = 100;

    /** So a pathological site cannot spin forever. */
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

    /** An empty site answers zero, indistinguishable from a wrong site number: both read as no board. */
    @Override
    public OptionalInt countPostings(String handle) {
        Optional<OracleHcmSite> site = OracleHcmSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            JsonNode response = api.searchRequisitions(site.get(), "", null, 1, 0);
            int total = total(response);
            return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A wrong host or site number answers with HTML or a 400, not a 404.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        OracleHcmSite site = OracleHcmSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not an Oracle HCM site: '" + handle
                        + "'. Expected host/siteNumber (e.g."
                        + " eofe.fa.us2.oraclecloud.com/BNY-Careers) or the career-site URL."));

        // Keyed by requisition id: a posting matched by two places or terms costs one detail call.
        Map<String, JsonNode> summaries = new LinkedHashMap<>();
        if (filter.locations().isEmpty()) {
            collectSummaries(site, "", null, filter, summaries);
        }
        for (String term : new LinkedHashSet<>(filter.locations())) {
            List<String> places = locationIds(site, term);
            if (places.isEmpty()) {
                collectSummaries(site, term, null, filter, summaries);
            }
            for (String locationId : places) {
                collectSummaries(site, "", locationId, filter, summaries);
            }
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

    /**
     * The pod's places named exactly by the term (city, state or country), so {@code india} is not
     * Indianapolis. Empty when nothing matches or the lookup fails: the caller then sends the term as a
     * keyword, which reads titles and descriptions far more than locations — measured on TI, it found 18 of
     * the 133 postings in India that the place id finds.
     */
    private List<String> locationIds(OracleHcmSite site, String term) {
        JsonNode suggestions;
        try {
            suggestions = api.locationSuggestions(site, term).path("items");
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No place lookup on " + site + " for '" + term + "'; sending it as a keyword",
                    e);
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode place : suggestions) {
            String id = place.path("Id").asText("");
            boolean named = Stream.of("City", "State", "Country")
                    .anyMatch(field -> place.path(field).asText("").trim().equalsIgnoreCase(term));
            if (!id.isBlank() && named && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void collectSummaries(OracleHcmSite site, String keyword, String locationId, BoardFilter filter,
                                  Map<String, JsonNode> into) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = api.searchRequisitions(site, keyword, locationId, PAGE_SIZE, offset);
            JsonNode list = requisitions(response);
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode summary : list) {
                String id = summary.path("Id").asText(null);
                // Title tested on the listing: every posting kept costs a second request.
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

    private RawItem toItem(OracleHcmSite site, String label, JsonNode summary) {
        String id = summary.path("Id").asText(null);
        String title = summary.path("Title").asText(null);
        if (id == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = first(api.requisition(site, id));
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Withdrawn since the listing, or transient: skipping one posting beats failing the site.
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
        // The site number is the fallback only because nothing better is published.
        String company = AtsNormalization.company(label, site.site());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", url);
        metadata.put("company", company);
        metadata.put("location", location);
        metadata.put("applyUrl", url);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: Oracle publishes a workplace type code.
        metadata.put("remote", "ORA_REMOTE".equals(detail.path("WorkplaceTypeCode").asText(""))
                || AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(detail.path("Category")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(posted));

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
                // ExternalPostedStartDate does not move on an edit, so the body is hashed too.
                AtsNormalization.withCompany(
                        "ohcm:" + id + ";posted:" + posted + ";body:" + content.hashCode(),
                        company, site.site()),
                AtsNormalization.instantOrNull(posted),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /** In a fixed order, so the checksum over it is stable; kept as HTML for the parser. */
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

    /** Oracle spells the country out, so nothing is appended (unlike Workday). */
    private static String location(JsonNode summary, JsonNode detail) {
        String primary = firstNonBlank(detail.path("PrimaryLocation").asText(null),
                summary.path("PrimaryLocation").asText(null));
        return primary == null ? "" : primary;
    }

    /** The search envelope nests one level deeper: {@code items[0].requisitionList}. */
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
