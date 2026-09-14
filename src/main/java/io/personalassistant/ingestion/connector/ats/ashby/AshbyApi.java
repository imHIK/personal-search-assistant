package io.personalassistant.ingestion.connector.ats.ashby;

import com.fasterxml.jackson.databind.JsonNode;

/** Port for Ashby's public job-board API, so the connector can be tested without network access. */
public interface AshbyApi {

    /**
     * Every live posting on a job board.
     *
     * @param boardName the board handle in {@code jobs.ashbyhq.com/<name>}
     * @return the raw {@code {"jobs": [...]}} response
     */
    JsonNode listJobs(String boardName);
}
