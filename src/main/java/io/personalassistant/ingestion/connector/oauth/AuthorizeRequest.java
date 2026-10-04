package io.personalassistant.ingestion.connector.oauth;

import java.util.Set;

/**
 * @param redirectUri explicit, because the app answers on more than one origin, and it must be byte-identical
 *                    in the code exchange
 * @param state single-use token tying the callback to this request
 */
public record AuthorizeRequest(String type, OAuthClient client, String redirectUri, String state,
                               Set<String> scopes) {

    public AuthorizeRequest {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("redirectUri is required");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state is required");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
