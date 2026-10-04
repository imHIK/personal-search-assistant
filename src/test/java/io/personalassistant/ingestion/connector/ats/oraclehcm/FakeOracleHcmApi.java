package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FakeOracleHcmApi implements OracleHcmApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final Map<String, Map<String, Object>> details = new LinkedHashMap<>();

    public final List<String> detailCalls = new ArrayList<>();

    public final List<String> keywords = new ArrayList<>();

    public final List<String> locationIds = new ArrayList<>();

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

    /** Null: the pod has no place lookup, so the platform falls back to keywords. */
    public List<Map<String, Object>> places;

    /** Every city, state and country the requisitions name, as the pod's geography would list them. */
    public FakeOracleHcmApi withPlaces() {
        places = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> s : summaries) {
            String[] parts = String.valueOf(s.get("PrimaryLocation")).split(", ");
            for (int i = 0; i < parts.length; i++) {
                String name = String.join(", ", Arrays.copyOfRange(parts, i, parts.length));
                if (seen.add(name)) {
                    String field = i == parts.length - 1 ? "Country" : i == 0 ? "City" : "State";
                    places.add(Map.of("Id", "loc-" + name, "Level", i == 0 ? 3 : 1, field, parts[i]));
                }
            }
        }
        return this;
    }

    @Override
    public JsonNode searchRequisitions(OracleHcmSite site, String keyword, String locationId, int limit,
                                       int offset) {
        if (offset == 0) {
            if (locationId != null) {
                locationIds.add(locationId);
            } else {
                keywords.add(keyword);
            }
        }
        // Oracle's finder searches the whole record, so a keyword can match the description as well as the
        // location. A location id matches the place or anything under it.
        List<Map<String, Object>> matching = summaries.stream()
                .filter(s -> keyword == null || keyword.isBlank() || indexed(s).contains(lower(keyword)))
                .filter(s -> locationId == null || String.valueOf(s.get("PrimaryLocation"))
                        .endsWith(locationId.substring("loc-".length())))
                .toList();
        int from = Math.min(offset, matching.size());
        int to = Math.min(from + limit, matching.size());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("TotalJobsCount", matching.size());
        envelope.put("requisitionList", matching.subList(from, to));
        return MAPPER.valueToTree(Map.of("items", List.of(envelope)));
    }

    /** Prefix-matched, as a typeahead is: {@code india} also suggests Indianapolis. */
    @Override
    public JsonNode locationSuggestions(OracleHcmSite site, String term) {
        if (places == null) {
            throw new AtsApiException(400, "no place lookup");
        }
        List<Map<String, Object>> matching = places.stream()
                .filter(p -> p.values().stream().anyMatch(v -> lower(String.valueOf(v)).startsWith(lower(term))))
                .toList();
        return MAPPER.valueToTree(Map.of("items", matching));
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
