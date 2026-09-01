package io.personalassistant.ingestion.connector.ats.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

/** Scriptable {@link GreenhouseApi} for connector tests — no network, mirrors {@code FakeDriveApi}. */
public class FakeGreenhouseApi implements GreenhouseApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> boards = new HashMap<>();

    /** Test observability: how many times the board was fetched. */
    public int listCalls;

    public FakeGreenhouseApi withBoard(String token, String json) {
        boards.put(token, json);
        return this;
    }

    @Override
    public JsonNode listJobs(String boardToken) {
        listCalls++;
        String json = boards.get(boardToken);
        if (json == null) {
            throw new io.personalassistant.ingestion.connector.ats.AtsApiException(
                    404, "no such board: " + boardToken);
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad fixture", e);
        }
    }
}
