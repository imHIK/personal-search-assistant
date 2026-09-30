package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.domain.model.Connection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * The connection's own {@code config.clientId}/{@code clientSecret} when it carries them, else
 * {@code app.oauth.<providerId>.client-id/secret}, read programmatically so a new provider needs only
 * properties.
 */
@ApplicationScoped
public class OAuthClients {

    private final Config config;

    @Inject
    public OAuthClients(Config config) {
        this.config = config;
    }

    /**
     * @param connection null on a first connect, when only the app-level client applies
     * @throws IllegalArgumentException if neither supplies a complete id and secret
     */
    public OAuthClient forProvider(String providerId, Connection connection) {
        Map<String, Object> connectionConfig = connection == null ? null : connection.config();
        String id = firstNonBlank(str(connectionConfig, "clientId"),
                property(providerId, "client-id"));
        String secret = firstNonBlank(str(connectionConfig, "clientSecret"),
                property(providerId, "client-secret"));
        if (id == null || secret == null) {
            throw new IllegalArgumentException("No OAuth client configured for " + providerId
                    + " — set app.oauth." + providerId + ".client-id/.client-secret, or put "
                    + "clientId/clientSecret on the connection's config");
        }
        return new OAuthClient(id, secret);
    }

    private String property(String providerId, String suffix) {
        return config.getOptionalValue("app.oauth." + providerId + "." + suffix, String.class)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    public boolean hasAppClient(String providerId) {
        return property(providerId, "client-id") != null && property(providerId, "client-secret") != null;
    }
}
