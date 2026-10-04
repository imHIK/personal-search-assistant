package io.personalassistant.ingestion.connector.ats.oraclehcm;

import com.fasterxml.jackson.databind.JsonNode;

/** The search returns metadata and an id; the description needs a per-requisition fetch. */
public interface OracleHcmApi {

    /**
     * @param keyword    free-text query, blank for none. It reads titles and descriptions far more than
     *                   locations, so it is only the fallback for placing a requisition
     * @param locationId a {@link #locationSuggestions} id, null for none; exact, unlike a keyword
     */
    JsonNode searchRequisitions(OracleHcmSite site, String keyword, String locationId, int limit, int offset);

    /**
     * The careers page's place typeahead: {@code items} of {@code {Id, Level, City, State, Country}}, from the
     * pod's geography rather than the site's postings. The site's own location facet would do, but it is
     * capped at the ten busiest places on some sites.
     */
    JsonNode locationSuggestions(OracleHcmSite site, String term);

    JsonNode requisition(OracleHcmSite site, String id);
}
