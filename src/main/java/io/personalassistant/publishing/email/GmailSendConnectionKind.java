package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.connection.ConnectionKind;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleConnectionTypes;
import io.personalassistant.ingestion.connector.google.GoogleOAuthProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * users.getProfile refuses a send-only token, so verification asks tokeninfo for the scopes. That proves more
 * than a refresh: an account connected before the scope was added refreshes fine and then fails every send.
 */
@ApplicationScoped
public class GmailSendConnectionKind implements ConnectionKind {

    private final GoogleAccessTokens tokens;
    private final GmailSendApi api;

    @Inject
    public GmailSendConnectionKind(GoogleAccessTokens tokens, GmailSendApi api) {
        this.tokens = tokens;
        this.api = api;
    }

    @Override
    public String id() {
        return GoogleConnectionTypes.GMAIL_SEND;
    }

    /** @throws IllegalArgumentException if the token lacks the send scope */
    @Override
    public void verify(Connection connection) {
        String bearer = tokens.authFor(connection).bearer();
        JsonNode info = api.tokenInfo(bearer);
        List<String> granted = List.of(info.path("scope").asText("").split(" "));
        if (!granted.contains(GoogleOAuthProvider.GMAIL_SEND_SCOPE)) {
            throw new IllegalArgumentException("This Google sign-in has not granted permission to send mail — "
                    + "reconnect it and approve sending");
        }
    }
}
