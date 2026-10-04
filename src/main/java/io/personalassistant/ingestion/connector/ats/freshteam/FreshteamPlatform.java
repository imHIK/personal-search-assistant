package io.personalassistant.ingestion.connector.ats.freshteam;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.BoardFilter;
import io.personalassistant.ingestion.connector.ats.BoardPlatform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Freshteam accounts, addressed by the {@code <sub>.freshteam.com} host. One request returns every job with
 * its description, so the filter hint is ignored. An unknown subdomain answers 200 with a page, not JSON,
 * which reads as no board.
 */
@ApplicationScoped
public class FreshteamPlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private final FreshteamApi api;

    @Inject
    public FreshteamPlatform(FreshteamApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "freshteam";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        Optional<FreshteamSite> site = FreshteamSite.parse(handle);
        if (site.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int jobs = api.listJobs(site.get()).path("jobs").size();
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
        FreshteamSite site = FreshteamSite.parse(handle).orElseThrow(
                () -> new IllegalArgumentException("Not a Freshteam account: '" + handle
                        + "'. Expected <sub>.freshteam.com or a URL on it."));
        JsonNode response = api.listJobs(site);
        Map<String, String> branches = names(response.path("branches"), "location", "city");
        Map<String, String> roles = names(response.path("job_roles"), "name", "name");
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : response.path("jobs")) {
            RawItem item = toItem(site, company, job, branches, roles);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(FreshteamSite site, String label, JsonNode job, Map<String, String> branches,
                           Map<String, String> roles) {
        String id = text(job, "id");
        String title = text(job, "title");
        if (id == null || title == null || job.path("deleted").asBoolean(false)) {
            return null;
        }
        String uri = text(job, "url");
        if (uri == null) {
            uri = "https://" + site.host() + "/jobs/" + firstNonBlank(text(job, "unique_id"), id);
        }
        String location = branches.get(text(job, "branch_id"));
        String companyName = AtsNormalization.company(label, site.host());
        String content = job.path("description").asText("");
        String created = text(job, "created_at");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", site.host());
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: remote is a real boolean here.
        metadata.put("remote", job.path("remote").asBoolean(false)
                || AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", roles.get(text(job, "job_role_id")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(created));

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
                // created_at does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "freshteam:" + id + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, site.host()),
                AtsNormalization.instantOrNull(created),
                raw,
                content,
                null,
                metadata,
                // A stated closing date beats the retention window.
                AtsNormalization.instantOrNull(text(job, "closing_date")),
                false);
    }

    /** id → the named field, for the branches and roles the jobs cite by id. */
    private static Map<String, String> names(JsonNode list, String field, String fallback) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode entry : list) {
            String id = text(entry, "id");
            String name = firstNonBlank(text(entry, field), text(entry, fallback));
            if (id != null && name != null) {
                out.put(id, name);
            }
        }
        return out;
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
