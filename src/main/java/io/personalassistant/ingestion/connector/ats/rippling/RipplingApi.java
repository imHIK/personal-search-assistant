package io.personalassistant.ingestion.connector.ats.rippling;

import com.fasterxml.jackson.databind.JsonNode;

/** The listing omits the description, which needs a per-job fetch. */
public interface RipplingApi {

    /** One row per job and location: a job in three cities is listed three times. 404 for no board. */
    JsonNode listJobs(String board);

    JsonNode job(String board, String uuid);
}
