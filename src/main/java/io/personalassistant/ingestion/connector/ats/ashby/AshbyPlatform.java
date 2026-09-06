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

/**
 * Ashby job-board connector. One iterable per board name (the {@code <name>} in
 * {@code jobs.ashbyhq.com/<name>}); the posting API returns the whole board in one call.
 *
 * <p>Ashby is the richest of the three sources: it states {@code isRemote} structurally rather than
 * leaving it to prose, and with {@code includeCompensation=true} it publishes real pay bands. Both are
 * preferred over the inferred values from {@code AtsNormalization} whenever present — a stated fact
 * always beats a guess.
 */
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
    public List<RawItem> fetch(String boardId, BoardFilter filter) {
        // Hint ignored: one request returns the whole board either way, so filtering
        // early would save nothing. The connector filters what comes back.
        JsonNode jobs = api.listJobs(boardId).path("jobs");
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : jobs) {
            RawItem item = toItem(boardId, job);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(String boardId, JsonNode job) {
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
        // publishedAt is the only timestamp Ashby publishes — there is no updatedAt on this API, and
        // reading the absent one used to yield a constant that made every posting look unchanged.
        String publishedAt = job.path("publishedAt").asText(null);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", boardId);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        // Stated beats inferred: Ashby publishes isRemote as a real field.
        metadata.put("remote", job.has("isRemote")
                ? job.path("isRemote").asBoolean(false)
                : AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(job.path("team")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(boardId, title, location));
        Instant postedAt = AtsNormalization.instantOrNull(publishedAt);
        putIfPresent(metadata, "postedAt", postedAt);
        applyCompensation(metadata, job, descriptionText);

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
                "ashby:" + id + ";v:" + AtsNormalization.changeStamp(title, location, body),
                AtsNormalization.instantOrNull(publishedAt),
                raw,
                body,
                null,
                metadata,
                // Ashby is the one board of the three that can state a close date. When it does, this
                // beats the knowledge-level window entirely (see RetentionSweeper).
                AtsNormalization.instantOrNull(job.path("closedAt").asText(null)),
                false);
    }

    /** Structured pay bands when Ashby publishes them, else the conservative text scrape. */
    private static void applyCompensation(Map<String, Object> metadata, JsonNode job, String descriptionText) {
        JsonNode summary = job.path("compensation").path("summaryComponents");
        for (JsonNode component : summary) {
            if (!"Salary".equalsIgnoreCase(component.path("compensationType").asText(""))) {
                continue;
            }
            JsonNode min = component.path("minValue");
            JsonNode max = component.path("maxValue");
            if (min.isNumber() && max.isNumber()) {
                metadata.put("compMin", min.asLong());
                metadata.put("compMax", max.asLong());
                putIfPresent(metadata, "compCurrency", nullableText(component.path("currencyCode")));
                return;
            }
        }
        AtsNormalization.CompRange comp = AtsNormalization.compRange(descriptionText);
        if (comp != null) {
            metadata.put("compMin", comp.min());
            metadata.put("compMax", comp.max());
            putIfPresent(metadata, "compCurrency", comp.currency());
        }
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
