package io.personalassistant.ingestion.connector.ats.keka;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keka careers portals. The job API is keyed by a board id that only the careers page states, so a poll is
 * two requests: the page, then every job with its description.
 */
@ApplicationScoped
public class KekaPlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** The page loads its own assets from {@code /ats/documents/<board id>/careerportal/...}. */
    private static final Pattern BOARD_ID = Pattern.compile(
            "/ats/documents/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/");

    private final KekaApi api;

    @Inject
    public KekaPlatform(KekaApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "keka";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        Optional<KekaSite> site = KekaSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int jobs = jobs(site.get()).size();
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        KekaSite site = KekaSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Keka careers portal: '" + handle
                        + "'. Expected <tenant>.keka.com or a URL on it."));
        // Hint ignored: one request returns the whole board either way.
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : jobs(site)) {
            RawItem item = toItem(site, company, job);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private JsonNode jobs(KekaSite site) {
        Matcher boardId = BOARD_ID.matcher(api.careersPage(site));
        if (!boardId.find()) {
            throw new AtsApiException("No Keka board id on " + site.careersPage());
        }
        JsonNode jobs = api.listJobs(site, boardId.group(1));
        if (jobs == null || !jobs.isArray()) {
            throw new AtsApiException("Unexpected Keka job list from " + site);
        }
        return jobs;
    }

    private RawItem toItem(KekaSite site, String label, JsonNode job) {
        String id = text(job, "id");
        String title = text(job, "title");
        if (id == null || title == null) {
            return null;
        }
        String uri = site.jobUrl(id);
        String location = location(job);
        String companyName = AtsNormalization.company(label, site.host());
        String content = job.path("description").asText("");
        String published = text(job, "publishedOn");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", site.host());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", text(job, "departmentName"));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(published));

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
                // publishedOn does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "keka:" + id + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, site.host()),
                AtsNormalization.instantOrNull(published),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /** City, state and country spelled out, so a filter naming either the city or the country matches. */
    private static String location(JsonNode job) {
        List<String> places = new ArrayList<>();
        for (JsonNode place : job.path("jobLocations")) {
            List<String> parts = new ArrayList<>();
            for (String field : List.of("city", "state", "countryName")) {
                String part = text(place, field);
                if (part != null) {
                    parts.add(part);
                }
            }
            String joined = parts.isEmpty()
                    ? Objects.requireNonNullElse(text(place, "name"), "")
                    : String.join(", ", parts);
            if (!joined.isEmpty() && !places.contains(joined)) {
                places.add(joined);
            }
        }
        return places.isEmpty() ? null : String.join("; ", places);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
