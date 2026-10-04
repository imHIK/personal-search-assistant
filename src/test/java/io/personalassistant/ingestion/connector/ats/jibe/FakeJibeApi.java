package io.personalassistant.ingestion.connector.ats.jibe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeJibeApi implements JibeApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<Map<String, Object>>> sites = new HashMap<>();

    public final List<Integer> pagesRequested = new ArrayList<>();

    public FakeJibeApi withJob(String host, String id, String title, String fullLocation,
                               String locationName, String description) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slug", id);
        data.put("req_id", id);
        data.put("title", title);
        data.put("full_location", fullLocation);
        data.put("location_name", locationName);
        data.put("description", description);
        data.put("qualifications", "<p>Java.</p>");
        data.put("posted_date", "2026-09-29T20:15:00+0000");
        data.put("update_date", "2026-09-30T06:39:59+0000");
        data.put("apply_url", "https://uscareers-acme.icims.com/jobs/" + id + "/login");
        data.put("categories", List.of(Map.of("name", "Engineering")));
        sites.computeIfAbsent(host, h -> new ArrayList<>()).add(Map.of("data", data));
        return this;
    }

    @Override
    public JsonNode listJobs(JibeSite site, int page, int limit) {
        pagesRequested.add(page);
        List<Map<String, Object>> all = sites.get(site.host());
        if (all == null) {
            throw new AtsApiException(404, "no such site: " + site);
        }
        int from = Math.min((page - 1) * limit, all.size());
        int to = Math.min(from + limit, all.size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jobs", all.subList(from, to));
        envelope.put("totalCount", all.size());
        return MAPPER.valueToTree(envelope);
    }
}
