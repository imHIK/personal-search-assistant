package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FakeWorkdayApi implements WorkdayApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final Map<String, Map<String, Object>> details = new LinkedHashMap<>();

    public final List<String> detailCalls = new ArrayList<>();

    public final List<String> searchTexts = new ArrayList<>();

    /**
     * {@code locationsText} is what the search reports (may be a count like "5 Locations"); {@code location}
     * is the detail's primary location.
     */
    public FakeWorkdayApi withPosting(String reqId, String title, String locationsText,
                                      String location, String country, String description) {
        String path = "/job/City/" + title.replace(' ', '-') + "_" + reqId;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", title);
        summary.put("externalPath", path);
        summary.put("locationsText", locationsText);
        summary.put("postedOn", "Posted Today");
        summaries.add(summary);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("jobReqId", reqId);
        info.put("location", location);
        info.put("additionalLocations", List.of());
        info.put("country", Map.of("descriptor", country));
        info.put("startDate", "2026-08-01");
        info.put("jobDescription", description);
        info.put("externalUrl", "https://acme.wd5.myworkdayjobs.com/site" + path);
        details.put(path, info);
        return this;
    }

    @Override
    public JsonNode searchJobs(WorkdaySite site, int limit, int offset, String searchText) {
        if (offset == 0) {
            searchTexts.add(searchText);
        }
        // Workday's index searches the whole record, so a query naming one of a posting's "5 Locations" still
        // finds it.
        List<Map<String, Object>> matching = searchText == null || searchText.isBlank()
                ? summaries
                : summaries.stream().filter(s -> indexedText(s).contains(lower(searchText))).toList();
        int from = Math.min(offset, matching.size());
        int to = Math.min(from + limit, matching.size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("total", matching.size());
        envelope.put("jobPostings", matching.subList(from, to));
        return MAPPER.valueToTree(envelope);
    }

    private String indexedText(Map<String, Object> summary) {
        Map<String, Object> info = details.get(String.valueOf(summary.get("externalPath")));
        StringBuilder out = new StringBuilder(String.valueOf(summary.get("locationsText")));
        if (info != null) {
            out.append(' ').append(info.get("location"))
               .append(' ').append(((Map<?, ?>) info.get("country")).get("descriptor"))
               .append(' ').append(info.get("jobDescription"));
        }
        return lower(out.toString());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public JsonNode posting(WorkdaySite site, String externalPath) {
        detailCalls.add(externalPath);
        Map<String, Object> info = details.get(externalPath);
        if (info == null) {
            throw new AtsApiException(404, "no such posting: " + externalPath);
        }
        return MAPPER.valueToTree(Map.of("jobPostingInfo", info));
    }
}
