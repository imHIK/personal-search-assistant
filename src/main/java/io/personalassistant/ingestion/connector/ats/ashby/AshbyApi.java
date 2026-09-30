package io.personalassistant.ingestion.connector.ats.ashby;

import com.fasterxml.jackson.databind.JsonNode;

public interface AshbyApi {

    /** @param boardName the handle in {@code jobs.ashbyhq.com/<name>} */
    JsonNode listJobs(String boardName);
}
