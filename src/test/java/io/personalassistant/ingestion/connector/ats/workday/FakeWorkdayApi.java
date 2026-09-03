package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scriptable {@link WorkdayApi} for platform tests — no network. Models the POST search + detail pair. */
public class FakeWorkdayApi implements WorkdayApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final Map<String, Map<String, Object>> details = new LinkedHashMap<>();

    /** Test observability: the externalPaths whose detail was fetched, in order. */
    public final List<String> detailCalls = new ArrayList<>();

    /**
     * Add a posting.
     *
     * @param locationsText what the SEARCH reports — may be a count like "5 Locations"
     * @param location      what the DETAIL reports as the primary location
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
    public JsonNode searchJobs(WorkdaySite site, int limit, int offset) {
        int from = Math.min(offset, summaries.size());
        int to = Math.min(from + limit, summaries.size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("total", summaries.size());
        envelope.put("jobPostings", summaries.subList(from, to));
        return MAPPER.valueToTree(envelope);
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
