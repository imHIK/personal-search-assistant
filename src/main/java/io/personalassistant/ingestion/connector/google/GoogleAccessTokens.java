package io.personalassistant.ingestion.connector.google;

import io.personalassistant.domain.model.Connection;

public interface GoogleAccessTokens {

    /**
     * The token and the account's rate limit together: two Google accounts share every host and URL, so a
     * bearer alone cannot tell them apart.
     *
     * @throws IllegalArgumentException if the connection carries no usable credentials
     * @throws io.personalassistant.ingestion.connector.oauth.CredentialsRejectedException if the refresh
     *                                                                                     token is revoked;
     *                                                                                     the connection is
     *                                                                                     marked ERROR first
     * @throws io.personalassistant.ingestion.connector.oauth.OAuthTransportException if a refresh failed
     *                                                                                transiently
     */
    GoogleAuth authFor(Connection connection);
}
