package io.personalassistant.ingestion.connector.oauth;

/**
 * The registered OAuth 2.0 application a flow runs as. Resolved per connection by
 * {@link OAuthClients} — either the connection's own client or the app-level one — so neither the
 * providers nor the use case have to know where it came from.
 *
 * @param id     OAuth client id
 * @param secret OAuth client secret
 */
public record OAuthClient(String id, String secret) {

    public OAuthClient {
        if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("OAuth client id and secret are both required");
        }
    }
}
