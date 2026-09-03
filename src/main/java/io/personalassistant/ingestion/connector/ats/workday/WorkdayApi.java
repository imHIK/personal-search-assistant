package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Port for Workday's public career-site API, so the platform can be tested without network access.
 *
 * <p>Two calls, like SmartRecruiters: the search returns titles and a path, and the description lives
 * behind a per-posting fetch. Unlike every other platform the search is a <strong>POST</strong>, with
 * the paging window in the body.
 *
 * @param site the {@code tenant/site/wd} triple identifying one career site
 */
public interface WorkdayApi {

    /** One page of a site's postings: {@code {"total", "jobPostings": [...]}}. */
    JsonNode searchJobs(WorkdaySite site, int limit, int offset);

    /** One posting in full, addressed by the {@code externalPath} the search returned. */
    JsonNode posting(WorkdaySite site, String externalPath);
}
