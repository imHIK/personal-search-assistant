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
 * Turns a {@link Connection}'s stored OAuth material into a usable bearer token, for any provider.
 *
 * <p>This is the piece worth writing once. Everything here is the same whoever the vendor is: prefer a
 * stored access token that has not expired, otherwise refresh from the refresh token, cache the result
 * in process, write it back onto the connection so it survives a restart, and react correctly when the
 * grant turns out to be dead. Only the HTTP call in the middle is vendor-specific, and that is
 * delegated to the {@link OAuthProvider}.
 *
 * <h2>Recognised {@code auth} keys (on {@link Connection#auth()})</h2>
 * <ul>
 *   <li>{@code accessToken} — a short-lived bearer token (optional if a refresh token is present)</li>
 *   <li>{@code expiresAtEpochSec} — when {@code accessToken} expires; treated as expired when absent</li>
 *   <li>{@code refreshToken} — long-lived token used to mint new access tokens (recommended)</li>
 * </ul>
 *
 * <h2>Recognised {@code config} keys (on {@link Connection#config()})</h2>
 * <ul>
 *   <li>{@code clientId} / {@code clientSecret} — the OAuth client to refresh as; falls back to
 *       {@code app.oauth.<providerId>.client-id/secret}. See {@link OAuthClients}.</li>
 * </ul>
 */
@ApplicationScoped
public class OAuthTokenService {

    private static final Logger LOG = Logger.getLogger(OAuthTokenService.class.getName());

    /** Refresh a little before the real expiry so an in-flight page never carries a just-expired token. */
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
     * @param limit the account's quota, charged for any refresh this triggers
     * @return a bearer token valid for calls on behalf of {@code connection}
     * @throws IllegalArgumentException     if the connection carries no usable credentials at all
     * @throws CredentialsRejectedException if the refresh token is dead — the connection is marked
     *                                      {@code ERROR} before this is thrown
     * @throws OAuthTransportException      if the refresh failed for a transient reason
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
            // The grant is dead: every later attempt would fail identically, so stop pretending this is
            // a blip. Drop the cached token (a stale entry would otherwise mask the failure until it
            // expired) and mark the connection so IngestionJob skips it from the very next tick and the
            // console can ask the user to reconnect — rather than waiting on the 30-minute health sweep.
            cache.remove(connection.id());
            markRejected(connection, e);
            throw e;
        }

        cache.put(connection.id(), new CachedToken(tokens.accessToken(), tokens.expiresAtEpochSec()));
        persist(connection, tokens);
        return tokens.accessToken();
    }

    /** Write the freshly-minted token back onto the connection so it survives restarts. Best-effort. */
    private void persist(Connection connection, OAuthTokens tokens) {
        try {
            Map<String, Object> newAuth = new LinkedHashMap<>(connection.auth());
            newAuth.put("accessToken", tokens.accessToken());
            newAuth.put("expiresAtEpochSec", tokens.expiresAtEpochSec());
            if (tokens.refreshToken() != null) {
                // Some providers rotate the refresh token on every use. Only ever write a real one:
                // a null here means "unchanged", never "clear it".
                newAuth.put("refreshToken", tokens.refreshToken());
            }
            connections.save(connection.withAuth(newAuth, Instant.now()));
        } catch (RuntimeException ignored) {
            // A persistence hiccup must not fail the grab — the in-process cache still holds the token.
        }
    }

    private void markRejected(Connection connection, CredentialsRejectedException cause) {
        // DISABLED is an operator decision, and the health sweep deliberately refuses to overwrite it.
        // Overwriting it here would make a connection someone switched off reappear as a broken one.
        if (connection.status() == ConnectionStatus.DISABLED) {
            return;
        }
        try {
            connections.save(connection.withStatus(ConnectionStatus.ERROR,
                    "The sign-in was rejected — reconnect this account. " + cause.getMessage()));
            LOG.warning("Connection " + connection.id() + " (" + connection.type()
                    + ") marked ERROR: credentials rejected by the provider");
        } catch (RuntimeException e) {
            // Losing the flag only costs us the early warning; the health sweep still catches it.
            LOG.log(Level.WARNING, "Could not mark connection " + connection.id() + " as ERROR", e);
        }
    }

    /** Forget any cached token for a connection — used when its credentials are replaced. */
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
