package io.personalassistant.ingestion.connector.ats.ashby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.ingestion.connector.ats.AtsApiException;
import java.util.HashMap;
import java.util.Map;

/** Scriptable {@link AshbyApi} for connector tests — no network. */
public class FakeAshbyApi implements AshbyApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> boards = new HashMap<>();

    public FakeAshbyApi withBoard(String name, String json) {
        boards.put(name, json);
        return this;
    }

    @Override
    public JsonNode listJobs(String boardName) {
        String json = boards.get(boardName);
        if (json == null) {
            throw new AtsApiException(404, "no such board: " + boardName);
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad fixture", e);
        }
    }
}
