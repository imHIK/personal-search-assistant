package io.personalassistant.ingestion.connector.google;

import io.personalassistant.domain.model.Connection;

/**
 * Resolves a usable OAuth 2.0 <em>bearer</em> access token from a {@link Connection}. This is the one
 * auth concern shared by every Google connector (Gmail, Drive), so it lives in the common
 * {@code google} package and is injected into each connector rather than re-implemented per source.
 *
 * <p>The OAuth material (access token, refresh token, expiry, optional client) travels in the opaque
 * {@link Connection#auth()} / {@link Connection#config()} blobs the core never inspects — see
 * {@link DefaultGoogleAccessTokens} for the exact keys. Taking a {@link Connection} (rather than a
 * {@code Knowledge}) is what lets several knowledges share one account's credentials and one refresh.
 */
public interface GoogleAccessTokens {

    /**
     * @return a bearer access token for calls on behalf of {@code connection}
     * @throws IllegalArgumentException if the connection carries no usable credentials
     * @throws GoogleApiException       if a token refresh was attempted and failed
     */
    String bearer(Connection connection);
}
