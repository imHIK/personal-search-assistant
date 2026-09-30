package io.personalassistant.ingestion.connector.oauth;

import java.util.Optional;

public interface OAuthProviderRegistry {

    /** @throws IllegalArgumentException if no provider carries that id */
    OAuthProvider get(String providerId);

    /** Empty for connectors that use no OAuth: an ordinary answer, hence Optional. */
    Optional<OAuthProvider> forType(String type);
}
