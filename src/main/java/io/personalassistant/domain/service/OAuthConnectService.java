package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Connection;

public interface OAuthConnectService {

    /**
     * @return the provider's consent URL, carrying a single-use state token
     * @throws IllegalArgumentException if the provider is unknown, does not handle the type, has no OAuth
     *                                  client configured, or the named connection is of another type
     * @throws java.util.NoSuchElementException if {@code connectionId} names no connection
     */
    String start(StartConnect request);

    /**
     * @throws IllegalArgumentException if the state token is unknown, replayed or expired, or the provider
     *                                  refused the code
     */
    Completed complete(String providerId, String state, String code);

    /**
     * Does not consume the token: used only to land a provider-reported error on the page the user started
     * from.
     *
     * @return the console origin, or null when the token is unknown
     */
    String returnOriginFor(String state);

    /** @param origin checked against the allow-list before it is used or echoed back */
    record StartConnect(String providerId, String type, String connectionId, String name,
                        String origin) {}

    record Completed(Connection connection, String returnTo) {}
}
