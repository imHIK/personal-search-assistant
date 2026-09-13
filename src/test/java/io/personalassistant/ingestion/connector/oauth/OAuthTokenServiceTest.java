package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.StubConfig;
import io.personalassistant.testsupport.StubOAuthProvider;
import io.personalassistant.testsupport.StubOAuthProviderRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercised entirely through a {@link StubOAuthProvider} — no Google anywhere — because that is the
 * claim being tested: refresh, caching, write-back and the reaction to a revoked grant belong to every
 * provider, not to the one that happened to need them first.
 */
class OAuthTokenServiceTest {

    private static final RateLimit NO_LIMIT = RateLimit.NONE;

    private InMemoryConnectionRepository connections;
    private StubOAuthProvider provider;
    private OAuthTokenService tokens;

    @BeforeEach
    void setUp() {
        connections = new InMemoryConnectionRepository();
        provider = new StubOAuthProvider("stub", SourceType.GMAIL);
        tokens = new OAuthTokenService(
                new StubOAuthProviderRegistry(provider),
                new OAuthClients(new StubConfig(Map.of(
                        "app.oauth.stub.client-id", "client",
                        "app.oauth.stub.client-secret", "secret"))),
                connections);
    }

    @Test
    void usesTheStoredAccessTokenWhileItIsStillValid() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stored", "expiresAtEpochSec", StubOAuthProvider.far(),
                "refreshToken", "r1")));

        Assertions.assertEquals("stored", tokens.bearer(connection, NO_LIMIT));
        Assertions.assertTrue(provider.refreshedWith.isEmpty(), "no refresh was needed");
    }

    @Test
    void refreshesAnExpiredTokenAndWritesItBackOntoTheConnection() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));

        Assertions.assertEquals("access-2", tokens.bearer(connection, NO_LIMIT));
        Assertions.assertEquals(java.util.List.of("r1"), provider.refreshedWith);

        Map<String, Object> stored = connections.store.get(connection.id()).auth();
        Assertions.assertEquals("access-2", stored.get("accessToken"));
        Assertions.assertEquals("r1", stored.get("refreshToken"), "the refresh token is preserved");
    }

    @Test
    void keepsTheStoredRefreshTokenWhenTheProviderReturnsNone() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));
        provider.onRefresh = () -> new OAuthTokens("access-2", null, StubOAuthProvider.far());

        tokens.bearer(connection, NO_LIMIT);

        Assertions.assertEquals("r1", connections.store.get(connection.id()).auth().get("refreshToken"));
    }

    @Test
    void storesARotatedRefreshTokenWhenTheProviderIssuesOne() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));
        provider.onRefresh = () -> new OAuthTokens("access-2", "r2", StubOAuthProvider.far());

        tokens.bearer(connection, NO_LIMIT);

        Assertions.assertEquals("r2", connections.store.get(connection.id()).auth().get("refreshToken"));
    }

    @Test
    void cachesTheMintedTokenSoASecondCallDoesNotRefreshAgain() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));

        tokens.bearer(connection, NO_LIMIT);
        tokens.bearer(connection, NO_LIMIT); // same stale connection object, as a second grab would hold

        Assertions.assertEquals(1, provider.refreshedWith.size(), "the second call came from the cache");
    }

    @Test
    void invalidateForcesTheNextCallToRefreshAgain() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));

        tokens.bearer(connection, NO_LIMIT);
        tokens.invalidate(connection.id());
        tokens.bearer(connection, NO_LIMIT);

        Assertions.assertEquals(2, provider.refreshedWith.size());
    }

    @Test
    void marksTheConnectionErrorWhenTheGrantIsRejected() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "revoked")));
        provider.onRefresh = () -> {
            throw new CredentialsRejectedException("invalid_grant");
        };

        Assertions.assertThrows(CredentialsRejectedException.class,
                () -> tokens.bearer(connection, NO_LIMIT));

        Connection after = connections.store.get(connection.id());
        Assertions.assertEquals(ConnectionStatus.ERROR, after.status());
        Assertions.assertNotNull(after.lastError());
    }

    @Test
    void leavesADisabledConnectionAlone() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "revoked"))
                .withStatus(ConnectionStatus.DISABLED, null));
        connections.save(connection);
        provider.onRefresh = () -> {
            throw new CredentialsRejectedException("invalid_grant");
        };

        Assertions.assertThrows(CredentialsRejectedException.class,
                () -> tokens.bearer(connection, NO_LIMIT));

        Assertions.assertEquals(ConnectionStatus.DISABLED, connections.store.get(connection.id()).status(),
                "DISABLED is an operator decision and must not be overwritten");
    }

    @Test
    void leavesTheConnectionAloneOnATransientFailure() {
        Connection connection = save(connection(Map.of(
                "accessToken", "stale", "expiresAtEpochSec", 1L, "refreshToken", "r1")));
        provider.onRefresh = () -> {
            throw new OAuthTransportException("HTTP 503");
        };

        Assertions.assertThrows(OAuthTransportException.class, () -> tokens.bearer(connection, NO_LIMIT));

        Assertions.assertEquals(ConnectionStatus.ACTIVE, connections.store.get(connection.id()).status(),
                "a bad minute at the provider is not a credential problem");
    }

    @Test
    void rejectsAConnectionCarryingNoCredentialsAtAll() {
        Connection connection = save(connection(Map.of()));

        Assertions.assertThrows(IllegalArgumentException.class, () -> tokens.bearer(connection, NO_LIMIT));
    }

    private Connection save(Connection connection) {
        return connections.save(connection);
    }

    private static Connection connection(Map<String, Object> auth) {
        Instant now = Instant.now();
        return new Connection("conn_1", "Stub account", "GMAIL", auth, Map.of(), null, true,
                ConnectionStatus.ACTIVE, null, now, now);
    }
}
