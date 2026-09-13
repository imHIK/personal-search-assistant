package io.personalassistant.ingestion.connector.oauth;

import java.util.Optional;

/** Lookup of the installed {@link OAuthProvider}s, by id (the URL segment) or by connection type. */
public interface OAuthProviderRegistry {

    /** @throws IllegalArgumentException if no provider carries that id */
    OAuthProvider get(String providerId);

    /**
     * The provider that authenticates {@code type}, or empty for connectors that use no OAuth (a local
     * folder, a public job board). Empty is an ordinary answer here, not a fault — which is why this
     * returns an {@link Optional} while {@link #get} throws.
     */
    Optional<OAuthProvider> forType(String type);
}
