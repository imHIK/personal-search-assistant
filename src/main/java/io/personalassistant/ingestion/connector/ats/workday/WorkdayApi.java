package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;

/** The search is a POST with the paging window in the body; the description needs a per-posting fetch. */
public interface WorkdayApi {

    /**
     * @param searchText queries Workday's index over the full record, unlike the summaries returned; the only
     *                   way to make a large site affordable (Lowe's: 12,424 postings blank, 48 for Bengaluru)
     */
    JsonNode searchJobs(WorkdaySite site, int limit, int offset, String searchText);

    JsonNode posting(WorkdaySite site, String externalPath);
}
