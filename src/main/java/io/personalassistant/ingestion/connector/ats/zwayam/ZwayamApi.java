package io.personalassistant.ingestion.connector.ats.zwayam;

import com.fasterxml.jackson.databind.JsonNode;

/** The search hits carry a summary only, so each job's description needs a second call. */
public interface ZwayamApi {

    /** Ten hits a page, newest change first. An unknown domain answers 200 with {@code data: null}. */
    JsonNode search(String domain, int offset);

    JsonNode job(String companyId, String jobUrl);

    /** Holds the folder the job pages live under and the company's own name. */
    JsonNode careerSite(String companyId);
}
