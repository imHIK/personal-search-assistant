package io.personalassistant.ingestion.connector.ats.freshteam;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The careers widget: {@code jobs} with descriptions, plus the {@code branches} and {@code job_roles} they cite
 * by id.
 */
public interface FreshteamApi {

    JsonNode listJobs(FreshteamSite site);
}
