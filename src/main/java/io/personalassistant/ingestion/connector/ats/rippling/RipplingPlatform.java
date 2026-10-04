package io.personalassistant.ingestion.connector.ats.rippling;

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
 * Rippling ATS boards. The listing carries metadata only, so a board of N jobs costs 1 + N requests. The
 * filter runs on the listing first, and a failed detail fetch skips that job rather than failing the board.
 */
@ApplicationScoped
public class RipplingPlatform implements BoardPlatform {

    private static final Logger LOG = Logger.getLogger(RipplingPlatform.class.getName());

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private final RipplingApi api;

    @Inject
    public RipplingPlatform(RipplingApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "rippling";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            int jobs = listed(api.listJobs(handle)).size();
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        List<RawItem> items = new ArrayList<>();
        for (Listed listed : listed(api.listJobs(handle))) {
            // Filtered before the detail call: a job passes when any of its locations does.
            if (!filter.matchesTitle(listed.title()) || !matchesAnyLocation(listed, filter)) {
                continue;
            }
            RawItem item = toItem(handle, company, listed);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** A job listed with no location passes, as a blank location does everywhere. */
    private static boolean matchesAnyLocation(Listed listed, BoardFilter filter) {
        return listed.locations().isEmpty()
                || listed.locations().stream().anyMatch(l -> AtsNormalization.matchesLocation(l, filter.locations()));
    }

    private record Listed(String uuid, String title, List<String> locations) {}

    /** One per job, in listing order: the listing repeats a job once per location. */
    private static List<Listed> listed(JsonNode response) {
        Map<String, Listed> jobs = new LinkedHashMap<>();
        if (response == null || !response.isArray()) {
            return List.of();
        }
        for (JsonNode row : response) {
            String uuid = row.path("uuid").asText("");
            String title = row.path("name").asText("").trim();
            if (uuid.isBlank() || title.isEmpty()) {
                continue;
            }
            Listed job = jobs.computeIfAbsent(uuid, u -> new Listed(u, title, new ArrayList<>()));
            String location = row.path("workLocation").path("label").asText("").trim();
            if (!location.isEmpty() && !job.locations().contains(location)) {
                job.locations().add(location);
            }
        }
        return List.copyOf(jobs.values());
    }

    private RawItem toItem(String board, String label, Listed listed) {
        JsonNode detail;
        try {
            detail = api.job(board, listed.uuid());
        } catch (RateLimitedException e) {
            throw e; // throttled, not this posting's fault: skipping would drop postings silently
        } catch (RuntimeException e) {
            // Withdrawn since the listing, or transient: skipping one job beats failing the board.
            LOG.log(Level.FINE, "Could not fetch Rippling job " + listed.uuid(), e);
            return null;
        }
        if (detail == null) {
            return null;
        }

        String title = firstNonBlank(detail.path("name").asText(null), listed.title()).trim();
        String location = String.join("; ", locations(detail, listed));
        String uri = firstNonBlank(detail.path("url").asText(null),
                "https://ats.rippling.com/" + board + "/jobs/" + listed.uuid());
        // The board's own name outranks the label, which only replaces the bare handle.
        String stated = detail.path("companyName").asText(null);
        String companyName = firstNonBlank(stated, AtsNormalization.company(label, board));
        String content = sections(detail);
        String createdOn = detail.path("createdOn").asText("");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", board);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(detail.path("department").path("base_department")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(createdOn));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", listed.uuid());
        raw.put("board", board);
        raw.put("contentType", "text/html");

        return new RawItem(
                listed.uuid(),
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                // createdOn does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "rippling:" + listed.uuid() + ";body:"
                                + AtsNormalization.changeStamp(title, location, content),
                        companyName, firstNonBlank(stated, board)),
                AtsNormalization.instantOrNull(createdOn),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    private static List<String> locations(JsonNode detail, Listed listed) {
        List<String> out = new ArrayList<>();
        for (JsonNode location : detail.path("workLocations")) {
            String text = location.asText("").trim();
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
        }
        return out.isEmpty() ? listed.locations() : out;
    }

    /** The role first, so the opening chunk is about the job and not the company blurb. */
    private static String sections(JsonNode detail) {
        JsonNode description = detail.path("description");
        return description.path("role").asText("") + description.path("company").asText("");
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
