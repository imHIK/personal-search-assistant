package io.personalassistant.ingestion.connector.ats.lever;

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
 * Lever job-board connector. One iterable per company handle (the {@code <site>} in
 * {@code jobs.lever.co/<site>}); {@code ?mode=json} returns the full posting list in one call.
 *
 * <p>Lever differs from Greenhouse in two ways that matter: it states dates as epoch millis rather
 * than ISO strings, and it exposes no update timestamp at all — only {@code createdAt}. See
 * {@link #checksumOf} for what that forces.
 */
@ApplicationScoped
public class LeverConnector extends SnapshotBoardConnector {

    private static final int SOURCE_RANK = 100;

    private final LeverApi api;

    @Inject
    public LeverConnector(LeverApi api) {
        this.api = api;
    }

    @Override
    public SourceType type() {
        return SourceType.LEVER;
    }

    @Override
    protected void verifyBoard(String boardId) {
        JsonNode response = api.listPostings(boardId);
        if (response == null || !response.isArray()) {
            throw new AtsApiException("Lever site '" + boardId + "' did not return a postings array");
        }
    }

    @Override
    protected List<RawItem> fetchBoard(String boardId) {
        JsonNode postings = api.listPostings(boardId);
        List<RawItem> items = new ArrayList<>();
        for (JsonNode posting : postings) {
            RawItem item = toItem(boardId, posting);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(String boardId, JsonNode posting) {
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

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", boardId);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText)
                || "remote".equalsIgnoreCase(categories.path("commitment").asText("")));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", nullableText(categories.path("team")));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(boardId, title, location));
        putIfPresent(metadata, "postedAt", createdAt);
        AtsNormalization.CompRange comp = AtsNormalization.compRange(descriptionText);
        if (comp != null) {
            metadata.put("compMin", comp.min());
            metadata.put("compMax", comp.max());
            // Always recorded with the range: compMin/compMax are plain numbers in the index, so a
            // corpus mixing INR and USD makes a bare numeric filter mean two things at once.
            putIfPresent(metadata, "compCurrency", comp.currency());
        }

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
                checksumOf(id, posting, body),
                createdAt,
                raw,
                body,
                null,
                metadata,
                null,
                false);
    }

    /**
     * Lever publishes no {@code updatedAt}, so {@code createdAt} alone would never change and an
     * edited posting would be skipped forever by change detection — a direct invariant-3 violation.
     * Hashing the body is the only signal available; it costs one hash per posting per poll, which is
     * cheap next to a wrong "nothing changed".
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
