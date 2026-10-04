package io.personalassistant.ingestion.connector.ats.jibe;

import com.fasterxml.jackson.databind.JsonNode;

/** The listing carries every field, description included, so there is no detail call. */
public interface JibeApi {

    /** @param page 1-based */
    JsonNode listJobs(JibeSite site, int page, int limit);
}
