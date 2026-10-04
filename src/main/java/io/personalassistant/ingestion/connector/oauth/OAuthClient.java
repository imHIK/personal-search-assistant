package io.personalassistant.ingestion.connector.oauth;

public record OAuthClient(String id, String secret) {

    public OAuthClient {
        if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("OAuth client id and secret are both required");
        }
    }
}
