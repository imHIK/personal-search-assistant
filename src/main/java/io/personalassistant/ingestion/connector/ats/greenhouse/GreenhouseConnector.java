package io.personalassistant.ingestion.connector.ats.greenhouse;

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
 * Greenhouse job-board connector. One iterable per board token (the {@code <token>} in
 * {@code boards.greenhouse.io/<token>}); {@code jobs?content=true} returns the whole board with
 * descriptions in a single call, which is what makes the snapshot shape work — see
 * {@link SnapshotBoardConnector}.
 */
@ApplicationScoped
public class GreenhouseConnector extends SnapshotBoardConnector {

    /**
     * Preference order when the same role is found on several sources. A direct ATS board is the
     * canonical listing and carries the real apply URL, so it outranks any aggregator.
     */
    private static final int SOURCE_RANK = 100;

    private final GreenhouseApi api;

    @Inject
    public GreenhouseConnector(GreenhouseApi api) {
        this.api = api;
    }

    @Override
    public SourceType type() {
        return SourceType.GREENHOUSE;
    }

    @Override
    protected void verifyBoard(String boardId) {
        JsonNode response = api.listJobs(boardId);
        if (response == null || !response.has("jobs")) {
            throw new AtsApiException("Greenhouse board '" + boardId + "' returned no jobs array");
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
            return null; // a posting without an id or title is unusable; skip rather than fail the page
        }
        String updatedAt = job.path("updated_at").asText("");
        String location = job.path("location").path("name").asText(null);
        String applyUrl = job.path("absolute_url").asText(null);
        String company = companyOf(job, boardId);
        // content is HTML-escaped in Greenhouse's payload; the HTML parser at index time unescapes
        // and strips it, so the entity keeps the source form rather than a lossy pre-flattened one.
        String content = job.path("content").asText("");
        String descriptionText = AtsNormalization.plainText(content);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", company);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        Instant postedAt = AtsNormalization.instantOrNull(job.path("first_published").asText(null));
        putIfPresent(metadata, "postedAt", postedAt);
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
        raw.put("boardToken", boardId);
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                applyUrl,
                // updated_at is what moves whenever a recruiter edits the posting, so it is the
                // change signal (invariant 3). Falling back to the content hash would re-index the
                // whole board on any whitespace change Greenhouse makes.
                "gh:" + id + ";upd:" + updatedAt,
                AtsNormalization.instantOrNull(updatedAt),
                raw,
                content,
                null,
                metadata,
                // Greenhouse states no close date, so entity-level expiry is always absent here and
                // the knowledge-level retention window governs.
                null,
                false);
    }

    private static String companyOf(JsonNode job, String boardId) {
        String name = job.path("company_name").asText(null);
        return name == null || name.isBlank() ? boardId : name;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
