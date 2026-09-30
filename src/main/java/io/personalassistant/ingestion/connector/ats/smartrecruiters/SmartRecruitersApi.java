package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;

/** The listing omits the description, which needs a per-posting fetch. */
public interface SmartRecruitersApi {

    /** @param limit the API caps it at 100 */
    JsonNode listPostings(String company, int limit, int offset);

    JsonNode posting(String company, String postingId);
}
