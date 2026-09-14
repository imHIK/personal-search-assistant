package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Port for Oracle Recruiting Cloud's public candidate-experience API, so the platform can be tested
 * without network access.
 *
 * <p>Two calls, the same shape as SmartRecruiters and Workday: the search returns metadata and an id,
 * and the description lives behind a per-requisition fetch.
 */
public interface OracleHcmApi {

    /**
     * One page of a site's requisitions.
     *
     * @param keyword Oracle's free-text query, or blank for the whole site. The same cost control
     *                Workday's {@code searchText} provides — BNY's site answers 1,386 requisitions for
     *                a blank query and 138 for {@code "Pune"} — and it is needed at least as badly
     *                here, because the listing carries no description and every kept requisition
     *                therefore costs a second call.
     */
    JsonNode searchRequisitions(OracleHcmSite site, String keyword, int limit, int offset);

    /** One requisition in full, addressed by the {@code Id} the search returned. */
    JsonNode requisition(OracleHcmSite site, String id);
}
