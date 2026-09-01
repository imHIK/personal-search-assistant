package io.personalassistant.ingestion.connector.ats.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Port for Greenhouse's public job-board API, so the connector can be tested without network access
 * (see {@code FakeGreenhouseApi}). Mirrors the {@code DriveApi}/{@code GmailApi} split.
 */
public interface GreenhouseApi {

    /**
     * Every posting on a board, with full descriptions.
     *
     * @param boardToken the board identifier in {@code boards.greenhouse.io/<token>}
     * @return the raw {@code {"jobs": [...]}} response
     */
    JsonNode listJobs(String boardToken);
}
