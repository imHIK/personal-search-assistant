package io.personalassistant.ingestion.connector.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers all {@link OAuthProvider} beans via CDI and indexes them both ways — by id for the API
 * path segment, by connection type for the token path. Adding a provider = adding a bean; no edits
 * here. Mirrors {@code CdiConnectorRegistry}.
 */
@ApplicationScoped
public class CdiOAuthProviderRegistry implements OAuthProviderRegistry {

    private final Map<String, OAuthProvider> byId = new LinkedHashMap<>();
    private final Map<String, OAuthProvider> byType = new LinkedHashMap<>();

    @Inject
    public CdiOAuthProviderRegistry(Instance<OAuthProvider> providers) {
        for (OAuthProvider provider : providers) {
            OAuthProvider clash = byId.put(provider.id(), provider);
            if (clash != null) {
                // Two providers answering the same URL segment would make the routing arbitrary.
                throw new IllegalStateException("Duplicate OAuth provider id " + provider.id());
            }
            for (String type : provider.supports()) {
                byType.put(type, provider);
            }
        }
    }

    @Override
    public OAuthProvider get(String providerId) {
        OAuthProvider provider = providerId == null ? null : byId.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("No OAuth provider registered as " + providerId);
        }
        return provider;
    }

    @Override
    public Optional<OAuthProvider> forType(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
