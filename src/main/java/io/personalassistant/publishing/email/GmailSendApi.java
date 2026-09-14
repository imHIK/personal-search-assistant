package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.google.GoogleAuth;

/**
 * The two Google endpoints sending mail needs — a narrow port so the publisher and the account check are
 * unit-testable against a fake, with all transport in {@link HttpGmailSendApi}.
 */
public interface GmailSendApi {

    /**
     * {@code users.messages.send}.
     *
     * @param rawBase64Url a complete RFC 2822 message, base64url-encoded
     * @return the sent message: {@code {id, threadId, labelIds}}
     */
    JsonNode send(GoogleAuth auth, String rawBase64Url);

    /**
     * Google's OAuth {@code tokeninfo} for an access token: {@code {scope, expires_in, …}}. Used to prove
     * an account granted the send scope, which no Gmail endpoint a send-only token can call would reveal.
     */
    JsonNode tokenInfo(String accessToken);
}
