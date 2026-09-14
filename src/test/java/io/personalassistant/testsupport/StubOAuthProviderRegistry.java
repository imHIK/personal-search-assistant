package io.personalassistant.testsupport;

import io.personalassistant.ingestion.connector.oauth.OAuthProvider;
import io.personalassistant.ingestion.connector.oauth.OAuthProviderRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Hand-wired {@link OAuthProviderRegistry} for unit tests (no CDI). */
public class StubOAuthProviderRegistry implements OAuthProviderRegistry {

    private final Map<String, OAuthProvider> byId = new LinkedHashMap<>();

    public StubOAuthProviderRegistry(OAuthProvider... providers) {
        for (OAuthProvider provider : providers) {
            byId.put(provider.id(), provider);
        }
    }

    @Override
    public OAuthProvider get(String providerId) {
        OAuthProvider provider = byId.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("No OAuth provider registered as " + providerId);
        }
        return provider;
    }

    @Override
    public Optional<OAuthProvider> forType(String type) {
        return byId.values().stream().filter(p -> p.supports().contains(type)).findFirst();
    }
}
