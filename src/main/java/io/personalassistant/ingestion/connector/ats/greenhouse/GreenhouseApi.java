package io.personalassistant.ingestion.connector.ats.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;

public interface GreenhouseApi {

    /** @param boardToken the handle in {@code boards.greenhouse.io/<token>} */
    JsonNode listJobs(String boardToken);
}
