package io.personalassistant.ingestion.connector.ats.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Port for SmartRecruiters' public postings API, so the platform can be tested without network access.
 *
 * <p>Two calls, not one, because the listing deliberately omits the job description: it carries only
 * metadata (title, location, function, dates), and the actual text lives behind a per-posting fetch.
 * That shapes everything about how this platform is driven — see {@code SmartRecruitersPlatform}.
 */
public interface SmartRecruitersApi {

    /**
     * One page of a company's postings.
     *
     * @param limit page size; the API caps this at 100 regardless of what is asked for
     * @return the raw {@code {"totalFound", "offset", "limit", "content": [...]}} envelope
     */
    JsonNode listPostings(String company, int limit, int offset);

    /** One posting in full, including the {@code jobAd.sections} that hold the description. */
    JsonNode posting(String company, String postingId);
}
