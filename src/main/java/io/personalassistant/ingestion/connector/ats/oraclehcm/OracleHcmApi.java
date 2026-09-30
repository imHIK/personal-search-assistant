package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;

/** The search returns metadata and an id; the description needs a per-requisition fetch. */
public interface OracleHcmApi {

    /**
     * @param keyword free-text query, blank for the whole site. The cost control: every requisition kept
     *                costs a second call
     */
    JsonNode searchRequisitions(OracleHcmSite site, String keyword, int limit, int offset);

    JsonNode requisition(OracleHcmSite site, String id);
}
