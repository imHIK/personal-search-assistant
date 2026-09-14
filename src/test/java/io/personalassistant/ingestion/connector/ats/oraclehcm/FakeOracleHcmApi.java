package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Scriptable {@link OracleHcmApi} for platform tests — no network. Models the search + detail pair. */
public class FakeOracleHcmApi implements OracleHcmApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final Map<String, Map<String, Object>> details = new LinkedHashMap<>();

    /** Test observability: the requisition ids whose detail was fetched, in order. */
    public final List<String> detailCalls = new ArrayList<>();

    /** Test observability: every keyword the platform sent, in order. */
    public final List<String> keywords = new ArrayList<>();

    public FakeOracleHcmApi withRequisition(String id, String title, String location,
                                            String description) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("Id", id);
        summary.put("Title", title);
        summary.put("PrimaryLocation", location);
        summary.put("PostedDate", "2026-09-04");
        summaries.add(summary);

        Map<String, Object> detail = new LinkedHashMap<>(summary);
        detail.put("RequisitionId", "3000" + id);
        detail.put("ExternalPostedStartDate", "2026-09-04T13:23:20+00:00");
        detail.put("ExternalDescriptionStr", description);
        detail.put("Category", "Engineering");
        detail.put("WorkplaceTypeCode", "ORA_HYBRID");
        details.put(id, detail);
        return this;
    }

    @Override
    public JsonNode searchRequisitions(OracleHcmSite site, String keyword, int limit, int offset) {
        if (offset == 0) {
            keywords.add(keyword);
        }
        // Stands in for Oracle's server-side finder, which reads the whole record — so a keyword can
        // match on the description as well as the location.
        List<Map<String, Object>> matching = keyword == null || keyword.isBlank()
                ? summaries
                : summaries.stream().filter(s -> indexed(s).contains(lower(keyword))).toList();
        int from = Math.min(offset, matching.size());
        int to = Math.min(from + limit, matching.size());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("TotalJobsCount", matching.size());
        envelope.put("requisitionList", matching.subList(from, to));
        return MAPPER.valueToTree(Map.of("items", List.of(envelope)));
    }

    @Override
    public JsonNode requisition(OracleHcmSite site, String id) {
        detailCalls.add(id);
        Map<String, Object> detail = details.get(id);
        if (detail == null) {
            throw new AtsApiException(404, "no such requisition: " + id);
        }
        return MAPPER.valueToTree(Map.of("items", List.of(detail)));
    }

    private String indexed(Map<String, Object> summary) {
        Map<String, Object> detail = details.get(String.valueOf(summary.get("Id")));
        return lower(summary.get("PrimaryLocation") + " " + summary.get("Title")
                + (detail == null ? "" : " " + detail.get("ExternalDescriptionStr")));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
