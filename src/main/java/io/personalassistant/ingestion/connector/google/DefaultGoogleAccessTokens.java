package io.personalassistant.ingestion.connector.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.ConfigText;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default {@link GoogleAccessTokens} implementation. It reads the OAuth material a {@link Connection}
 * stores and returns a valid bearer token, minting a fresh one from the refresh token when the stored
 * access token is missing or (near) expiry.
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
 *   <li>{@code clientId} / {@code clientSecret} — the OAuth client used to refresh; fall back to the
 *       app-level {@code app.ingestion.google.client-id/secret} config when absent</li>
 * </ul>
 *
 * <p>A refreshed token is <strong>persisted back onto the connection</strong> (and cached in-process,
 * keyed by connection id) so it survives restarts and is shared by every knowledge bound to that
 * connection — the payoff of hanging credentials off a connection rather than each knowledge.
 */
@ApplicationScoped
public class DefaultGoogleAccessTokens implements GoogleAccessTokens {

    /** Refresh a little before the real expiry so an in-flight page never carries a just-expired token. */
    private static final long EXPIRY_SKEW_SECONDS = 60;

    @ConfigProperty(name = "app.ingestion.google.token-url",
            defaultValue = "https://oauth2.googleapis.com/token")
    String tokenUrl;

    /**
     * App-level OAuth client, used when a connection carries no {@code clientId}/{@code clientSecret}
     * of its own. Optional: normally supplied via {@code ${GOOGLE_OAUTH_CLIENT_ID:}}, which is blank
     * when the env var is unset. See {@link ConfigText} for why these cannot be plain Strings.
     */
    @ConfigProperty(name = "app.ingestion.google.client-id")
    Optional<String> defaultClientId;

    @ConfigProperty(name = "app.ingestion.google.client-secret")
    Optional<String> defaultClientSecret;

    private final ConnectionRepository connections;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    @Inject
    public DefaultGoogleAccessTokens(ConnectionRepository connections) {
        this.connections = connections;
    }

    @Override
    public String bearer(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("No connection to authenticate with");
        }
        Map<String, Object> auth = connection.auth();

        String accessToken = str(auth, "accessToken");
        Long expiresAt = lng(auth, "expiresAtEpochSec");
        if (accessToken != null && !isExpired(expiresAt)) {
            return accessToken;
        }

        String refreshToken = str(auth, "refreshToken");
        if (refreshToken != null) {
            return refreshed(connection);
        }
        if (accessToken != null) {
            return accessToken; // best effort: no refresh token, use whatever we were given
        }
        throw new IllegalArgumentException(
                "Google connection " + connection.id() + " has no accessToken or refreshToken");
    }

    private String refreshed(Connection connection) {
        CachedToken cached = cache.get(connection.id());
        if (cached != null && !isExpired(cached.expiresAtEpochSec)) {
            return cached.accessToken;
        }
        Map<String, Object> auth = connection.auth();
        Map<String, Object> config = connection.config();
        String refreshToken = str(auth, "refreshToken");
        String clientId = firstNonBlank(str(config, "clientId"), ConfigText.orNull(defaultClientId));
        String clientSecret = firstNonBlank(str(config, "clientSecret"),
                ConfigText.orNull(defaultClientSecret));
        if (clientId == null || clientSecret == null) {
            throw new IllegalArgumentException(
                    "refreshToken present but no OAuth client-id/secret configured (set "
                            + "app.ingestion.google.client-id/secret or the connection's config.clientId/clientSecret)");
        }

        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GoogleApiException(response.statusCode(),
                        "Google token refresh failed: " + snippet(response.body()));
            }
            JsonNode json = mapper.readTree(response.body());
            String token = json.path("access_token").asText(null);
            if (token == null) {
                throw new GoogleApiException(response.statusCode(),
                        "Google token refresh returned no access_token: " + snippet(response.body()));
            }
            long ttl = json.path("expires_in").asLong(3600);
            long expiresAt = Instant.now().getEpochSecond() + ttl;
            cache.put(connection.id(), new CachedToken(token, expiresAt));
            persist(connection, token, expiresAt);
            return token;
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Google token refresh request failed", e);
        }
    }

    /** Write the freshly-minted token back onto the connection so it survives restarts. Best-effort. */
    private void persist(Connection connection, String accessToken, long expiresAtEpochSec) {
        try {
            Map<String, Object> newAuth = new LinkedHashMap<>(connection.auth());
            newAuth.put("accessToken", accessToken);
            newAuth.put("expiresAtEpochSec", expiresAtEpochSec);
            connections.save(connection.withAuth(newAuth, Instant.now()));
        } catch (RuntimeException ignored) {
            // A persistence hiccup must not fail the grab — the in-process cache still holds the token.
        }
    }

    private static boolean isExpired(Long expiresAtEpochSec) {
        return expiresAtEpochSec == null
                || expiresAtEpochSec <= Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static Long lng(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }

    private record CachedToken(String accessToken, long expiresAtEpochSec) {
    }
}
