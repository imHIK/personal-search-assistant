package io.personalassistant.ingestion.connector.ats.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.HashMap;
import java.util.Map;

/** Scriptable {@link LeverApi} for connector tests — no network. */
public class FakeLeverApi implements LeverApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> sites = new HashMap<>();

    public FakeLeverApi withSite(String site, String json) {
        sites.put(site, json);
        return this;
    }

    @Override
    public JsonNode listPostings(String site) {
        String json = sites.get(site);
        if (json == null) {
            throw new AtsApiException(404, "no such site: " + site);
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad fixture", e);
        }
    }
}
