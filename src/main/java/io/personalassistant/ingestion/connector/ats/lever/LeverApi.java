package io.personalassistant.ingestion.connector.ats.lever;

import com.fasterxml.jackson.databind.JsonNode;

/** Port for Lever's public postings API, so the connector can be tested without network access. */
public interface LeverApi {

    /**
     * Every published posting for a company.
     *
     * @param site the company handle in {@code jobs.lever.co/<site>}
     * @return the raw JSON array of postings
     */
    JsonNode listPostings(String site);
}
