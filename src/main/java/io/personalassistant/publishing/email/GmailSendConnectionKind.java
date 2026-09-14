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
 * The send-only Gmail account ({@code GMAIL_SEND}) the email publisher sends through. The first
 * connection type that belongs to nothing in the knowledge flow.
 *
 * <p>Verification cannot do what the Gmail connector does — {@code users.getProfile} refuses a token
 * holding only {@code gmail.send}, which is the point of that scope. So it asks Google's tokeninfo which
 * scopes the token carries. That proves more than a successful refresh would: an account connected
 * before the scope was added to the consent screen refreshes happily and then fails every send.
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

    /**
     * @throws IllegalArgumentException if the token lacks the send scope
     * @throws RuntimeException         whatever obtaining the token or calling tokeninfo raised
     */
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
