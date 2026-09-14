package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.domain.model.Connection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.Config;

/**
 * Resolves which registered OAuth application a connection runs as: the connection's own
 * {@code config.clientId} / {@code config.clientSecret} when it carries them, otherwise the app-level
 * {@code app.oauth.<providerId>.client-id} / {@code .client-secret}.
 *
 * <p>Keyed by provider id and read through {@link Config} programmatically rather than as injected
 * {@code @ConfigProperty} fields, precisely so a new provider needs no code here — it adds two
 * properties and is done. The per-connection override stays because two accounts on the same provider
 * can legitimately belong to different registered apps.
 */
@ApplicationScoped
public class OAuthClients {

    private final Config config;

    @Inject
    public OAuthClients(Config config) {
        this.config = config;
    }

    /**
     * @return the client to run {@code providerId}'s flow as for this connection; {@code connection}
     *         may be null during a first-time connect, where only the app-level client can apply
     * @throws IllegalArgumentException if neither source supplies a complete id + secret pair
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

    /** Whether an app-level client exists for this provider, i.e. whether the flow can even be offered. */
    public boolean hasAppClient(String providerId) {
        return property(providerId, "client-id") != null && property(providerId, "client-secret") != null;
    }
}
