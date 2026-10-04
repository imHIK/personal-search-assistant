package io.personalassistant.ingestion.connector.ats.kula;

import com.fasterxml.jackson.databind.JsonNode;

/** One page of job posts, descriptions included; {@code meta.pages} says how many. 404 for no account. */
public interface KulaApi {

    /** @param page 1-based */
    JsonNode listJobPosts(String account, int page);
}
