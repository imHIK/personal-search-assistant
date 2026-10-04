package io.personalassistant.ingestion.connector.ats.keka;

import com.fasterxml.jackson.databind.JsonNode;

public interface KekaApi {

    /** The careers page HTML: the only place the board id is published. */
    String careersPage(KekaSite site);

    /** Every open job, description included. */
    JsonNode listJobs(KekaSite site, String boardId);
}
