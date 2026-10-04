package io.personalassistant.ingestion.connector.ats.turbohire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
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
 * TurboHire careers pages, addressed by the account ({@code flipkart.turbohire.co}, or the bare account). A
 * poll is a token, the organisation, the job list and one detail call per job kept: the filter runs on the
 * list first, and a failed detail fetch skips that job rather than failing the board. The API is the one the
 * careers page itself calls; it is not documented. The list also names the recruiter who posted each job,
 * which is deliberately never copied.
 */
@ApplicationScoped
public class TurboHirePlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(TurboHirePlatform.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private final TurboHireApi api;

    @Inject
    public TurboHirePlatform(TurboHireApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "turbohire";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        Optional<TurboHireSite> site = TurboHireSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int jobs = board(site.get()).jobs().size();
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        TurboHireSite site = TurboHireSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a TurboHire account: '" + handle
                        + "'. Expected <account>.turbohire.co or the bare account."));
        Board board = board(site);
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : board.jobs()) {
            // Filtered before the detail call; title first, the cheaper and sharper test.
            String location = location(job);
            if (!filter.matchesTitle(text(job, "JobTitle"))
                    || !AtsNormalization.matchesLocation(location, filter.locations())) {
                continue;
            }
            RawItem item = toItem(site, board, company, job, location);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private record Board(String token, String orgName, List<JsonNode> jobs) {}

    private Board board(TurboHireSite site) {
        String token = text(api.anonymousToken(site), "access_token");
        if (token == null) {
            throw new AtsApiException("No anonymous TurboHire token for " + site);
        }
        JsonNode org = api.organization(site, token);
        String orgId = text(org, "OrgID");
        if (orgId == null) {
            throw new AtsApiException("No TurboHire organisation for " + site);
        }
        List<JsonNode> jobs = new ArrayList<>();
        api.careerPageJobs(site, token, orgId).path("Result").forEach(jobs::add);
        return new Board(token, text(org, "OrgName"), jobs);
    }

    private RawItem toItem(TurboHireSite site, Board board, String label, JsonNode summary, String location) {
        String id = text(summary, "JobId");
        String title = text(summary, "JobTitle");
        if (id == null || title == null) {
            return null;
        }
        JsonNode detail;
        try {
            detail = api.job(site, board.token(), id);
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Closed since the list, or transient: skipping one job beats failing the board.
            LOG.log(Level.FINE, "Could not fetch TurboHire job " + id, e);
            return null;
        }
        if (detail == null || !detail.path("IsPublic").asBoolean(true)) {
            return null;
        }

        String uri = site.jobUrl(id);
        // The client is stated only when the page shows it; otherwise the field is masked.
        String client = summary.path("ShowClientName").asBoolean(false) ? text(summary, "ClientName") : null;
        String companyName = firstNonBlank(client, AtsNormalization.company(label, board.orgName()));
        String content = sections(detail);
        String published = text(summary, "PublishedDate");
        String updated = text(summary, "UpdatedDate");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", site.toString());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", text(summary, "Department"));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(utc(published)));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("account", site.account());
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                AtsNormalization.withCompany(
                        "turbohire:" + id + ";upd:" + (updated == null ? "" : updated)
                                + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, board.orgName()),
                AtsNormalization.instantOrNull(utc(updated)),
                raw,
                content,
                null,
                metadata,
                // A stated expiry for the public page beats the retention window.
                AtsNormalization.instantOrNull(utc(text(summary.path("ExpiryDates"), "CAREERPAGE"))),
                false);
    }

    /** The job, then its responsibilities, then the company blurb, so the opening chunk is about the job. */
    private static String sections(JsonNode detail) {
        StringBuilder out = new StringBuilder();
        for (String field : List.of("JobDescriptionV2", "RolesAndResponsibilitiesV2", "ClientDescV2")) {
            String text = detail.path(field).asText("");
            if (field.equals("JobDescriptionV2") && text.isBlank()) {
                text = detail.path("JobDescription").asText("");
            }
            if (!text.isBlank()) {
                out.append(text);
            }
        }
        return out.toString();
    }

    /** {@code Location} is a JSON array encoded as a string: {@code [{"Address": "..."}]}. */
    static String location(JsonNode job) {
        String encoded = text(job, "Location");
        if (encoded == null) {
            return null;
        }
        List<String> places = new ArrayList<>();
        try {
            for (JsonNode place : MAPPER.readTree(encoded)) {
                String address = text(place, "Address");
                if (address != null && !places.contains(address)) {
                    places.add(address);
                }
            }
        } catch (Exception e) {
            return encoded;
        }
        return places.isEmpty() ? null : String.join("; ", places);
    }

    /** Some dates carry no zone; the API's are UTC. */
    private static String utc(String value) {
        if (value == null || value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return value;
        }
        return value + "Z";
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
