package io.personalassistant.ingestion.connector.ats.kula;

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
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Kula careers pages, addressed by the account slug in {@code careers.kula.ai/<slug>}. The listing carries
 * every description, so a board costs one request per 99 posts and the filter hint is ignored. The API is the
 * one the careers page itself calls; it is not documented.
 */
@ApplicationScoped
public class KulaPlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    /** Well above the largest board seen. */
    private static final int MAX_PAGES = 30;

    private final KulaApi api;

    @Inject
    public KulaPlatform(KulaApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "kula";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            int posts = api.listJobPosts(handle, 1).path("meta").path("count").asInt(0);
            return posts > 0 ? OptionalInt.of(posts) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String account, String company, BoardFilter filter) {
        List<RawItem> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode response = api.listJobPosts(account, page);
            JsonNode posts = response.path("data");
            if (!posts.isArray() || posts.isEmpty()) {
                break;
            }
            for (JsonNode post : posts) {
                RawItem item = toItem(account, company, post);
                if (item != null) {
                    items.add(item);
                }
            }
            if (page >= response.path("meta").path("pages").asInt(page)) {
                break;
            }
        }
        return items;
    }

    private RawItem toItem(String account, String label, JsonNode post) {
        String id = text(post, "id");
        String title = text(post, "title");
        // An internal-only post is listed to employees, not candidates.
        if (id == null || title == null || !post.path("listed").asBoolean(true)
                || "internal".equals(text(post, "kind"))) {
            return null;
        }
        JsonNode job = post.path("ats_job");
        String uri = "https://careers.kula.ai/" + account + "/" + id;
        String location = location(job);
        String companyName = AtsNormalization.company(label, account);
        String content = job.path("job_description").asText("");
        String launched = text(post, "launch_at");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", account);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: workplace is remote / hybrid / onsite.
        metadata.put("remote", "remote".equalsIgnoreCase(text(job, "workplace"))
                || AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", text(job.path("ats_department"), "name"));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(launched));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("account", account);
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                // launch_at does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "kula:" + id + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, account),
                AtsNormalization.instantOrNull(launched),
                raw,
                content,
                null,
                metadata,
                // A stated end beats the retention window.
                AtsNormalization.instantOrNull(text(post, "end_at")),
                false);
    }

    private static String location(JsonNode job) {
        List<String> places = new ArrayList<>();
        for (JsonNode office : job.path("offices")) {
            String place = text(office, "location");
            if (place == null) {
                place = text(office, "name");
            }
            if (place != null && !places.contains(place)) {
                places.add(place);
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
