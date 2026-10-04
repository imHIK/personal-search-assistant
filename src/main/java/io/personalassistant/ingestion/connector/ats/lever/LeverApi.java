package io.personalassistant.ingestion.connector.ats.lever;

import com.fasterxml.jackson.databind.JsonNode;

public interface LeverApi {

    /** @param site the handle in {@code jobs.lever.co/<site>} */
    JsonNode listPostings(String site);
}
