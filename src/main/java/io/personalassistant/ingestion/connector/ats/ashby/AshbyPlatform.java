package io.personalassistant.ingestion.connector.ats.ashby;

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
import java.util.OptionalInt;

@ApplicationScoped
public class AshbyPlatform implements BoardPlatform {

    private static final int SOURCE_RANK = 100;

    private final AshbyApi api;

    @Inject
    public AshbyPlatform(AshbyApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "ashby";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            JsonNode response = api.listJobs(handle);
            if (response == null || !response.has("jobs")) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(response.path("jobs").size());
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String boardId, String company, BoardFilter filter) {
        // Hint ignored: one request returns the whole board either way.
        JsonNode jobs = api.listJobs(boardId).path("jobs");
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : jobs) {
            RawItem item = toItem(boardId, company, job);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(String boardId, String label, JsonNode job) {
        String id = job.path("id").asText(null);
        String title = job.path("title").asText(null);
        if (id == null || title == null) {
            return null;
        }
        String location = job.path("location").asText(null);
        String applyUrl = firstNonBlank(job.path("applyUrl").asText(null), job.path("jobUrl").asText(null));
        String html = job.path("descriptionHtml").asText("");
        String plain = job.path("descriptionPlain").asText("");
        String body = html.isBlank() ? plain : html;
        String descriptionText = html.isBlank() ? plain : AtsNormalization.plainText(html);
        // publishedAt is the only timestamp this API has; there is no updatedAt.
        String publishedAt = job.path("publishedAt").asText(null);
        String company = AtsNormalization.company(label, boardId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", company);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred.
        metadata.put("remote", job.has("isRemote")
                ? job.path("isRemote").asBoolean(false)
                : AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(job.path("team")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        Instant postedAt = AtsNormalization.instantOrNull(publishedAt);
        putIfPresent(metadata, "postedAt", postedAt);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("board", boardId);
        raw.put("contentType", html.isBlank() ? "text/plain" : "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                html.isBlank() ? "text/plain" : "text/html",
                title,
                applyUrl,
                AtsNormalization.withCompany(
                        "ashby:" + id + ";v:" + AtsNormalization.changeStamp(title, location, body),
                        company, boardId),
                AtsNormalization.instantOrNull(publishedAt),
                raw,
                body,
                null,
                metadata,
                // Ashby can state a close date, which beats the retention window.
                AtsNormalization.instantOrNull(job.path("closedAt").asText(null)),
                false);
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
