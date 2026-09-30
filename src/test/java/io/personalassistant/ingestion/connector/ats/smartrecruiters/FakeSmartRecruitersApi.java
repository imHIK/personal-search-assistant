package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeSmartRecruitersApi implements SmartRecruitersApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<Map<String, Object>>> listings = new HashMap<>();
    /** "company/id" -> the detail document. */
    private final Map<String, Map<String, Object>> details = new HashMap<>();
    private final Map<String, RuntimeException> detailFailures = new HashMap<>();

    public final List<String> detailCalls = new ArrayList<>();

    public FakeSmartRecruitersApi withPosting(String company, String id, String title,
                                              String fullLocation, String description) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("city", fullLocation);
        location.put("fullLocation", fullLocation);
        location.put("remote", false);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", title);
        summary.put("location", location);
        summary.put("releasedDate", "2026-08-01T00:00:00.000Z");
        listings.computeIfAbsent(company, c -> new ArrayList<>()).add(summary);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", "Job Description");
        section.put("text", description);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        detail.put("name", title);
        detail.put("location", location);
        detail.put("applyUrl", "https://jobs.smartrecruiters.com/" + company + "/" + id);
        detail.put("company", Map.of("name", company));
        detail.put("jobAd", Map.of("sections", Map.of("jobDescription", section)));
        details.put(company + "/" + id, detail);
        return this;
    }

    public FakeSmartRecruitersApi failDetail(String company, String id, RuntimeException failure) {
        detailFailures.put(company + "/" + id, failure);
        return this;
    }

    @Override
    public JsonNode listPostings(String company, int limit, int offset) {
        List<Map<String, Object>> all = listings.getOrDefault(company, List.of());
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("totalFound", all.size());
        envelope.put("offset", offset);
        envelope.put("limit", limit);
        envelope.put("content", all.subList(from, to));
        return MAPPER.valueToTree(envelope);
    }

    @Override
    public JsonNode posting(String company, String postingId) {
        detailCalls.add(postingId);
        RuntimeException failure = detailFailures.get(company + "/" + postingId);
        if (failure != null) {
            throw failure;
        }
        Map<String, Object> detail = details.get(company + "/" + postingId);
        if (detail == null) {
            throw new AtsApiException(404, "no such posting: " + postingId);
        }
        return MAPPER.valueToTree(detail);
    }
}
