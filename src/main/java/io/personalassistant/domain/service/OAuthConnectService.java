package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Connection;

/**
 * Use-case port for connecting an account through an OAuth provider's consent flow, replacing the
 * copy-paste of a refresh token obtained by hand.
 *
 * <p>Two halves of one browser round-trip: {@link #start} produces the URL to send the user to, and
 * {@link #complete} turns the code the provider sends back into stored credentials on a
 * {@link Connection}. Nothing here knows which provider is involved — that is resolved through the
 * provider registry — so a new OAuth application needs no change to this interface or its
 * implementation.
 */
public interface OAuthConnectService {

    /**
     * Begin a consent flow.
     *
     * @param request what is being connected and where the browser came from
     * @return the provider's consent URL, with a single-use state token embedded
     * @throws IllegalArgumentException if the provider is unknown, does not handle the requested type,
     *                                  has no OAuth client configured, or the named connection is not
     *                                  of that type
     * @throws java.util.NoSuchElementException if {@code connectionId} names no connection
     */
    String start(StartConnect request);

    /**
     * Finish a consent flow: exchange the code and create or re-credential the connection.
     *
     * @return where the connection now stands, and where to send the browser back to
     * @throws IllegalArgumentException if the state token is unknown, replayed or expired, or the
     *                                  provider refused the code
     */
    Completed complete(String providerId, String state, String code);

    /**
     * Look up where to bounce a browser back to for a state token, without consuming it. Used only to
     * land a provider-reported error (a declined consent) on the page the user started from.
     *
     * @return the console origin, or null when the token is unknown
     */
    String returnOriginFor(String state);

    /**
     * @param providerId   the provider's id, from the URL segment
     * @param type         the connection type an account is being connected for
     * @param connectionId existing connection to re-credential, or null to create a new one
     * @param name         label to give a newly created connection, or null for a generated one
     * @param origin       the console origin the request came from, e.g. {@code http://localhost:5173};
     *                     validated against the configured allow-list before it is used or echoed back
     */
    record StartConnect(String providerId, String type, String connectionId, String name,
                        String origin) {}

    /**
     * @param connection the connection that now holds the credentials
     * @param returnTo   the console origin to redirect the browser to
     */
    record Completed(Connection connection, String returnTo) {}
}
