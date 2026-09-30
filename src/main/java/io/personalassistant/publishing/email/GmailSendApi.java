package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;

public interface GmailSendApi {

    /** @param rawBase64Url a complete RFC 2822 message, base64url-encoded */
    JsonNode send(GoogleAuth auth, String rawBase64Url);

    /**
     * Proves the account granted the send scope, which no endpoint a send-only token can call would reveal.
     */
    JsonNode tokenInfo(String accessToken);
}
