package io.personalassistant.app;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.service.ConnectionService;
import io.personalassistant.domain.service.OAuthConnectService;
import io.personalassistant.ingestion.connector.oauth.AuthorizeRequest;
import io.personalassistant.ingestion.connector.oauth.OAuthClient;
import io.personalassistant.ingestion.connector.oauth.OAuthClients;
import io.personalassistant.ingestion.connector.oauth.OAuthProvider;
import io.personalassistant.ingestion.connector.oauth.OAuthProviderRegistry;
import io.personalassistant.ingestion.connector.oauth.OAuthStateStore;
import io.personalassistant.ingestion.connector.oauth.OAuthTokenService;
import io.personalassistant.ingestion.connector.oauth.OAuthTokens;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Credentials go through ConnectionService, not the repository, so a completed consent is verified against
 * its connector before it is reported connected.
 */
@ApplicationScoped
public class DefaultOAuthConnectService implements OAuthConnectService {

    private static final Logger LOG = Logger.getLogger(DefaultOAuthConnectService.class.getName());

    /**
     * An allow-list, not whatever the browser claims: the callback redirects to this value, so an unchecked
     * origin would be an open redirect.
     */
    @ConfigProperty(name = "app.oauth.allowed-origins",
            defaultValue = "http://localhost:8080,http://localhost:5173")
    String allowedOrigins;

    private final OAuthProviderRegistry providers;
    private final OAuthClients clients;
    private final OAuthStateStore states;
    private final OAuthTokenService tokenService;
    private final ConnectionService connectionService;
    private final ConnectionRepository connections;

    @Inject
    public DefaultOAuthConnectService(OAuthProviderRegistry providers, OAuthClients clients,
                                      OAuthStateStore states, OAuthTokenService tokenService,
                                      ConnectionService connectionService,
                                      ConnectionRepository connections) {
        this.providers = providers;
        this.clients = clients;
        this.states = states;
        this.tokenService = tokenService;
        this.connectionService = connectionService;
        this.connections = connections;
    }

    @Override
    public String start(StartConnect request) {
        OAuthProvider provider = providers.get(request.providerId());
        if (!provider.supports().contains(request.type())) {
            throw new IllegalArgumentException(
                    provider.id() + " does not authenticate " + request.type());
        }
        Connection existing = existing(request.connectionId(), request.type());
        OAuthClient client = clients.forProvider(provider.id(), existing);

        String origin = allowedOrigin(request.origin());
        String redirectUri = redirectUri(origin, provider.id());
        String state = states.issue(new OAuthStateStore.PendingConnect(
                provider.id(), request.type(), request.connectionId(), request.name(),
                redirectUri, origin, Instant.now()));

        return provider.authorizeUrl(new AuthorizeRequest(request.type(), client, redirectUri, state,
                provider.scopesFor(request.type())));
    }

    @Override
    public Completed complete(String providerId, String state, String code) {
        OAuthStateStore.PendingConnect pending = states.consume(state)
                .orElseThrow(() -> new IllegalArgumentException(
                        "This sign-in link has already been used or has expired — start again"));
        if (!pending.providerId().equals(providerId)) {
            throw new IllegalArgumentException("This sign-in was not started for " + providerId);
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("The provider returned no authorization code");
        }

        OAuthProvider provider = providers.get(providerId);
        Connection existing = existing(pending.connectionId(), pending.type());
        OAuthClient client = clients.forProvider(providerId, existing);

        // Providers compare the redirect URI literally, so it is replayed from the state rather than rebuilt.
        OAuthTokens tokens = provider.exchangeCode(code, pending.redirectUri(), client);

        Connection connection = existing == null
                ? create(pending, provider, tokens)
                : recredential(existing, tokens);
        LOG.info("Connected " + connection.type() + " connection " + connection.id()
                + " via " + providerId);
        return new Completed(connection, pending.returnTo());
    }

    @Override
    public String returnOriginFor(String state) {
        return states.peek(state).map(OAuthStateStore.PendingConnect::returnTo).orElse(null);
    }

    private Connection create(OAuthStateStore.PendingConnect pending, OAuthProvider provider,
                              OAuthTokens tokens) {
        String name = pending.name() == null || pending.name().isBlank()
                ? defaultName(provider.id())
                : pending.name();
        return connectionService.create(new ConnectionService.NewConnection(
                name, pending.type(), authBlob(tokens, null), null, null, false));
    }

    private Connection recredential(Connection existing, OAuthTokens tokens) {
        // Drop the cached bearer first: the update below re-verifies, and would otherwise be answered from
        // the stale cache.
        tokenService.invalidate(existing.id());
        return connectionService.update(existing.id(), new ConnectionService.ConnectionEdit(
                null, authBlob(tokens, existing), null, null));
    }

    /**
     * Keeps the stored refresh token when the provider returned none: overwriting a working one with null
     * kills the connection for good.
     */
    private Map<String, Object> authBlob(OAuthTokens tokens, Connection existing) {
        Map<String, Object> auth = new LinkedHashMap<>();
        if (existing != null) {
            auth.putAll(existing.auth());
        }
        auth.put("accessToken", tokens.accessToken());
        auth.put("expiresAtEpochSec", tokens.expiresAtEpochSec());
        if (tokens.refreshToken() != null) {
            auth.put("refreshToken", tokens.refreshToken());
        }
        return auth;
    }

    private Connection existing(String connectionId, String type) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        Connection connection = connections.findById(connectionId)
                .orElseThrow(() -> new NoSuchElementException("No connection with id " + connectionId));
        if (!connection.type().equals(type)) {
            throw new IllegalArgumentException("Connection " + connectionId + " is a "
                    + connection.type() + " connection, not " + type);
        }
        return connection;
    }

    /** @return {@code origin} when allow-listed, otherwise the first configured origin */
    private String allowedOrigin(String origin) {
        List<String> allowed = allowed();
        String trimmed = origin == null ? "" : stripTrailingSlash(origin.trim());
        return allowed.contains(trimmed) ? trimmed : allowed.get(0);
    }

    private List<String> allowed() {
        List<String> allowed = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(DefaultOAuthConnectService::stripTrailingSlash)
                .toList();
        if (allowed.isEmpty()) {
            throw new IllegalStateException("app.oauth.allowed-origins is empty; the OAuth flow "
                    + "has nowhere to send the browser back to");
        }
        return allowed;
    }

    private static String redirectUri(String origin, String providerId) {
        return origin + "/api/connections/oauth/" + providerId + "/callback";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String defaultName(String providerId) {
        return Character.toUpperCase(providerId.charAt(0)) + providerId.substring(1) + " account";
    }
}
