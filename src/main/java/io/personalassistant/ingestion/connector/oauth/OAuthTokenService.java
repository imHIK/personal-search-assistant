package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a connection's OAuth material into a bearer for any provider: a stored unexpired access token, else a
 * refresh, cached in process and written back to survive restarts. Recognised {@code auth} keys: accessToken,
 * expiresAtEpochSec (absent means expired), refreshToken. {@code config.clientId}/{@code clientSecret}
 * override the app-level client (see OAuthClients).
 */
@ApplicationScoped
public class OAuthTokenService {

    private static final Logger LOG = Logger.getLogger(OAuthTokenService.class.getName());

    /** Refresh a little early, so an in-flight page never carries a just-expired token. */
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final OAuthProviderRegistry providers;
    private final OAuthClients clients;
    private final ConnectionRepository connections;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    @Inject
    public OAuthTokenService(OAuthProviderRegistry providers, OAuthClients clients,
                             ConnectionRepository connections) {
        this.providers = providers;
        this.clients = clients;
        this.connections = connections;
    }

    /**
     * @param limit charged for any refresh this triggers
     * @throws IllegalArgumentException if the connection carries no usable credentials
     * @throws CredentialsRejectedException if the refresh token is dead; the connection is marked ERROR first
     * @throws OAuthTransportException if the refresh failed transiently
     */
    public String bearer(Connection connection, RateLimit limit) {
        if (connection == null) {
            throw new IllegalArgumentException("No connection to authenticate with");
        }
        Map<String, Object> auth = connection.auth();

        String accessToken = str(auth, "accessToken");
        if (accessToken != null && !isExpired(lng(auth, "expiresAtEpochSec"))) {
            return accessToken;
        }
        if (str(auth, "refreshToken") != null) {
            return refreshed(connection, limit);
        }
        if (accessToken != null) {
            return accessToken; // best effort: no refresh token, use whatever we were given
        }
        throw new IllegalArgumentException(
                "Connection " + connection.id() + " has no accessToken or refreshToken");
    }

    private String refreshed(Connection connection, RateLimit limit) {
        CachedToken cached = cache.get(connection.id());
        if (cached != null && !isExpired(cached.expiresAtEpochSec)) {
            return cached.accessToken;
        }
        OAuthProvider provider = providers.forType(connection.type())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No OAuth provider handles " + connection.type()
                                + "; cannot refresh connection " + connection.id()));
        OAuthClient client = clients.forProvider(provider.id(), connection);

        OAuthTokens tokens;
        try {
            tokens = provider.refresh(str(connection.auth(), "refreshToken"), client, limit);
        } catch (CredentialsRejectedException e) {
            // The grant is dead: drop the cached token and mark the connection ERROR now, so ingestion skips
            // it from the next tick and the console asks for a reconnect.
            cache.remove(connection.id());
            markRejected(connection, e);
            throw e;
        }

        cache.put(connection.id(), new CachedToken(tokens.accessToken(), tokens.expiresAtEpochSec()));
        persist(connection, tokens);
        return tokens.accessToken();
    }

    private void persist(Connection connection, OAuthTokens tokens) {
        try {
            Map<String, Object> newAuth = new LinkedHashMap<>(connection.auth());
            newAuth.put("accessToken", tokens.accessToken());
            newAuth.put("expiresAtEpochSec", tokens.expiresAtEpochSec());
            if (tokens.refreshToken() != null) {
                // Some providers rotate the refresh token. Only ever write a real one: null means unchanged,
                // never clear.
                newAuth.put("refreshToken", tokens.refreshToken());
            }
            connections.save(connection.withAuth(newAuth, Instant.now()));
        } catch (RuntimeException ignored) {
            // A persistence hiccup must not fail the grab: the cache still holds the token.
        }
    }

    private void markRejected(Connection connection, CredentialsRejectedException cause) {
        // DISABLED is an operator decision; overwriting it would make a switched-off connection reappear as
        // broken.
        if (connection.status() == ConnectionStatus.DISABLED) {
            return;
        }
        try {
            connections.save(connection.withStatus(ConnectionStatus.ERROR,
                    "The sign-in was rejected — reconnect this account. " + cause.getMessage()));
            LOG.warning("Connection " + connection.id() + " (" + connection.type()
                    + ") marked ERROR: credentials rejected by the provider");
        } catch (RuntimeException e) {
            // Losing the flag costs only the early warning; the health sweep still catches it.
            LOG.log(Level.WARNING, "Could not mark connection " + connection.id() + " as ERROR", e);
        }
    }

    public void invalidate(String connectionId) {
        cache.remove(connectionId);
    }

    private static boolean isExpired(Long expiresAtEpochSec) {
        return expiresAtEpochSec == null
                || expiresAtEpochSec <= Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Long lng(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private record CachedToken(String accessToken, long expiresAtEpochSec) {
    }
}
