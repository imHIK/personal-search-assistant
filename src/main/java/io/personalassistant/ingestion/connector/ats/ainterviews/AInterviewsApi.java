package io.personalassistant.ingestion.connector.ats.ainterviews;

import com.fasterxml.jackson.databind.JsonNode;

/** The listing carries the whole posting. 404 for no board. */
public interface AInterviewsApi {

    JsonNode listJobs(String board);
}
