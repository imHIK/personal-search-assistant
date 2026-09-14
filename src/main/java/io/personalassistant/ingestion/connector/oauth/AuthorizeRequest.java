package io.personalassistant.ingestion.connector.oauth;

import java.util.Set;

/**
 * Everything a provider needs to build the consent URL the user's browser is sent to.
 *
 * <p>{@code redirectUri} travels explicitly rather than being derived by the provider because the same
 * deployment answers on more than one origin (:8080 in production, :5173 behind the Vite dev proxy),
 * and because the value used here must be byte-identical to the one sent in the later code exchange —
 * providers compare the two literally and reject a mismatch.
 *
 * @param type        the connection type this consent is being gathered for
 * @param client      the OAuth application to run as
 * @param redirectUri where the provider sends the browser back to
 * @param state       opaque single-use token tying the callback to this request
 * @param scopes      the permissions to ask for
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
