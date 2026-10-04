package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
import java.util.List;

public interface GmailApi {

    /**
     * Newest first.
     *
     * @param labelIds empty means all mail
     */
    JsonNode listMessages(GoogleAuth auth, List<String> labelIds, String query,
                          String pageToken, int maxResults);

    JsonNode getMessage(GoogleAuth auth, String id);

    JsonNode listLabels(GoogleAuth auth);

    JsonNode getProfile(GoogleAuth auth);
}
