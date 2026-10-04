package io.personalassistant.ingestion.connector.ats.rippling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeRipplingApi implements RipplingApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<Map<String, Object>>> listings = new HashMap<>();
    /** "board/uuid" -> the detail document. */
    private final Map<String, Map<String, Object>> details = new HashMap<>();
    private final Map<String, RuntimeException> detailFailures = new HashMap<>();

    public final List<String> detailCalls = new ArrayList<>();

    /** Listed once per location, as the real listing does. */
    public FakeRipplingApi withJob(String board, String uuid, String title, String role, String... locations) {
        for (String location : locations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid);
            row.put("name", title);
            row.put("url", "https://ats.rippling.com/" + board + "/jobs/" + uuid);
            row.put("workLocation", Map.of("label", location, "id", location));
            listings.computeIfAbsent(board, b -> new ArrayList<>()).add(row);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("uuid", uuid);
        detail.put("name", title);
        detail.put("description", Map.of("company", "<p>About " + board + ".</p>", "role", role));
        detail.put("workLocations", List.of(locations));
        detail.put("department", Map.of("name", "830 Eng", "base_department", "Engineering"));
        detail.put("createdOn", "2026-09-25T11:41:16.881000-07:00");
        detail.put("url", "https://ats.rippling.com/" + board + "/jobs/" + uuid);
        detail.put("companyName", board);
        details.put(board + "/" + uuid, detail);
        return this;
    }

    public FakeRipplingApi failDetail(String board, String uuid, RuntimeException failure) {
        detailFailures.put(board + "/" + uuid, failure);
        return this;
    }

    @Override
    public JsonNode listJobs(String board) {
        List<Map<String, Object>> rows = listings.get(board);
        if (rows == null) {
            throw new AtsApiException(404, "no such board: " + board);
        }
        return MAPPER.valueToTree(rows);
    }

    @Override
    public JsonNode job(String board, String uuid) {
        detailCalls.add(uuid);
        RuntimeException failure = detailFailures.get(board + "/" + uuid);
        if (failure != null) {
            throw failure;
        }
        Map<String, Object> detail = details.get(board + "/" + uuid);
        if (detail == null) {
            throw new AtsApiException(404, "no such job: " + uuid);
        }
        return MAPPER.valueToTree(detail);
    }
}
