package io.personalassistant.ingestion.connector.ats.turbohire;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The calls the public careers page makes, every one under an anonymous token. The listing carries a 500
 * character teaser, so each job's description needs a detail call.
 */
public interface TurboHireApi {

    /** {@code access_token}, valid for about an hour. */
    JsonNode anonymousToken(TurboHireSite site);

    /** {@code OrgID} and {@code OrgName}; 404 for no such account. */
    JsonNode organization(TurboHireSite site, String token);

    /**
     * {@code Result}: the whole public careers page in one response. Page type 0 is that page; types 1 and 2
     * are the internal and referral job lists, which answer anonymously too but are not public postings.
     */
    JsonNode careerPageJobs(TurboHireSite site, String token, String orgId);

    JsonNode job(TurboHireSite site, String token, String jobId);
}
