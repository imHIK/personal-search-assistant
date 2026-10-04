package io.personalassistant.ingestion.connector.ats.ainterviews;

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

/** AInterviews job boards, addressed by the slug in {@code ainterviews.com/job_board/<slug>/}. */
@ApplicationScoped
public class AInterviewsPlatform implements BoardPlatform {

    /** A direct board outranks any aggregator. */
    private static final int SOURCE_RANK = 100;

    private static final String SITE = "https://ainterviews.com/job_board/";

    private final AInterviewsApi api;

    @Inject
    public AInterviewsPlatform(AInterviewsApi api) {
        this.api = api;
    }

    @Override
    public String id() {
        return "ainterviews";
    }

    @Override
    public OptionalInt countPostings(String handle) {
        try {
            int jobs = api.listJobs(handle).path("jobs").size();
            return jobs > 0 ? OptionalInt.of(jobs) : OptionalInt.empty();
        } catch (RuntimeException e) {
            // A miss is the normal outcome for all but one platform, so it must not propagate.
            return OptionalInt.empty();
        }
    }

    @Override
    public List<RawItem> fetch(String board, String company, BoardFilter filter) {
        // Hint ignored: one request returns the whole board either way.
        List<RawItem> items = new ArrayList<>();
        for (JsonNode job : api.listJobs(board).path("jobs")) {
            RawItem item = toItem(board, company, job);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private RawItem toItem(String board, String label, JsonNode job) {
        String id = text(job, "id");
        String title = text(job, "title");
        if (id == null || title == null) {
            return null;
        }
        String uri = SITE + board + "/job/" + id + "/";
        String location = text(job, "location");
        String companyName = AtsNormalization.company(label, board);
        String content = job.path("description").asText("") + job.path("company_description").asText("");
        String posted = text(job, "posted_date");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("uri", uri);
        metadata.put("company", companyName);
        metadata.put("location", location);
        metadata.put("applyUrl", uri);
        metadata.put("board", board);
        metadata.put("platform", id());
        metadata.put("sourceRank", SOURCE_RANK);
        metadata.put("remote", AtsNormalization.isRemote(location, AtsNormalization.plainText(content)));
        putIfPresent(metadata, "seniority", AtsNormalization.seniority(title));
        putIfPresent(metadata, "team", text(job, "category"));
        putIfPresent(metadata, "dedupeKey", AtsNormalization.dedupeKey(companyName, title, location));
        putIfPresent(metadata, "postedAt", AtsNormalization.instantOrNull(posted));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("board", board);
        raw.put("contentType", "text/html");

        return new RawItem(
                id,
                EntityType.JOB_POSTING,
                "text/html",
                title,
                uri,
                // posted_date does not move on an edit, so the body is hashed.
                AtsNormalization.withCompany(
                        "ainterviews:" + id + ";body:" + AtsNormalization.changeStamp(title, location, content),
                        companyName, board),
                AtsNormalization.instantOrNull(posted),
                raw,
                content,
                null,
                metadata,
                // application_deadline is null on every posting seen, so the retention window governs.
                null,
                false);
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
