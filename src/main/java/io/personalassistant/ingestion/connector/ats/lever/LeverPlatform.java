package io.personalassistant.ingestion.connector.ats.lever;

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
public class LeverPlatform implements BoardPlatform {

    private static final int SOURCE_RANK = 100;

    private final LeverApi api;

    @Inject
    public LeverPlatform(LeverApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "lever";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            JsonNode response = api.listPostings(handle);
            if (response == null || !response.isArray() || response.isEmpty()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(response.size());
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String boardId, String company, BoardFilter filter) {
        // Hint ignored: one request returns the whole board either way.
        JsonNode postings = api.listPostings(boardId);
        List<RawItem> items = new ArrayList<>();
        for (JsonNode posting : postings) {
            RawItem item = toItem(boardId, company, posting);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(String boardId, String label, JsonNode posting) {
        String id = posting.path("id").asText(null);
        String title = posting.path("text").asText(null);
        if (id == null || title == null) {
            return null;
        }
        JsonNode categories = posting.path("categories");
        String location = categories.path("location").asText(null);
        String applyUrl = posting.path("hostedUrl").asText(null);
        String description = posting.path("descriptionPlain").asText("");
        String html = posting.path("description").asText("");
        String body = html.isBlank() ? description : html;
        String descriptionText = html.isBlank() ? description : AtsNormalization.plainText(html);
        Instant createdAt = AtsNormalization.instantOrNull(longOrNull(posting.path("createdAt")));
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
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText)
                || "remote".equalsIgnoreCase(categories.path("commitment").asText("")));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(categories.path("team")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        putIfPresent(metadata, "postedAt", createdAt);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("site", boardId);
        raw.put("contentType", html.isBlank() ? "text/plain" : "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                html.isBlank() ? "text/plain" : "text/html",
                title,
                applyUrl,
                AtsNormalization.withCompany(checksumOf(id, posting, body), company, boardId),
                createdAt,
                raw,
                body,
                null,
                metadata,
                null,
                false);
    }

    /**
     * No updatedAt, only createdAt, which would never change and leave an edited posting skipped forever, so
     * the body is hashed.
     */
    private static String checksumOf(String id, JsonNode posting, String body) {
        int bodyHash = body == null ? 0 : body.hashCode();
        return "lever:" + id + ";created:" + posting.path("createdAt").asText("") + ";body:" + bodyHash;
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asLong();
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
