package io.personalassistant.ingestion.connector.ats.greenhouse;

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
public class GreenhousePlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private final GreenhouseApi api;

    @Inject
    public GreenhousePlatform(GreenhouseApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "greenhouse";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            JsonNode response = api.listJobs(handle);
            int jobs = response == null ? 0 : response.path("jobs").size();
            // An empty board is a miss: a dormant one would otherwise shadow the company's live board
            // on a platform probed later.
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
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
            return null; // a posting without an id or title is unusable; skip rather than fail the page
        }
        String updatedAt = job.path("updated_at").asText("");
        String location = job.path("location").path("name").asText(null);
        String applyUrl = job.path("absolute_url").asText(null);
        String company = companyOf(job, label, boardId);
        // content is HTML-escaped; the HTML parser unescapes it at index time, so the entity keeps the source
        // form.
        String content = job.path("content").asText("");
        String descriptionText = AtsNormalization.plainText(content);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", applyUrl);
        metadata.put("company", company);
        metadata.put("location", location);
        metadata.put("applyUrl", applyUrl);
        metadata.put("board", boardId);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, descriptionText));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(company, title, location));
        Instant postedAt = AtsNormalization.instantOrNull(job.path("first_published").asText(null));
        putIfPresent(metadata, "postedAt", postedAt);

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
                // Not updated_at: it moves in bulk (178 of GitLab's 227 postings share it), so the stamp
                // covers what is indexed instead.
                AtsNormalization.withCompany(
                        "gh:" + id + ";v:" + AtsNormalization.changeStamp(title, location, content),
                        company, companyOf(job, null, boardId)),
                AtsNormalization.instantOrNull(updatedAt),
                raw,
                content,
                null,
                metadata,
                // No close date is published, so the retention window governs.
                null,
                false);
    }

    /**
     * The board's own company_name first: a real name, it outranks even the label, which only replaces the
     * token.
     */
    private static String companyOf(JsonNode job, String label, String boardId) {
        String name = job.path("company_name").asText(null);
        return name == null || name.isBlank() ? AtsNormalization.company(label, boardId) : name;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
