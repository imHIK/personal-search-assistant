package io.personalassistant.ingestion.connector.ats.ashby;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import io.personalassistant.ingestion.connector.ats.AtsNormalization;
import io.personalassistant.ingestion.connector.ats.SnapshotBoardConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
public class AshbyConnector extends SnapshotBoardConnector {

    private static final int SOURCE_RANK = 100;

    private final AshbyApi api;

    @Inject
    public AshbyConnector(AshbyApi api) {
        this.api = api;
    }

    @Override
    public SourceType type() {
        return SourceType.ASHBY;
    }

    @Override
    protected void verifyBoard(String boardId) {
        JsonNode response = api.listJobs(boardId);
        if (response == null || !response.has("jobs")) {
            throw new AtsApiException("Ashby board '" + boardId + "' returned no jobs array");
        }
    }

    @Override
    protected List<RawItem> fetchBoard(String boardId) {
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
        String publishedAt = job.path("publishedAt").asText(null);
        String updatedAt = job.path("updatedAt").asText("");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", boardId);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
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
                "ashby:" + id + ";upd:" + updatedAt,
                AtsNormalization.instantOrNull(updatedAt),
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
