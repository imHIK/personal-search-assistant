package io.personalassistant.app;

import io.personalassistant.connection.CdiConnectionKindRegistry;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.OAuthConnectService;
import io.personalassistant.ingestion.connector.oauth.AuthorizeRequest;
import io.personalassistant.ingestion.connector.oauth.OAuthClients;
import io.personalassistant.ingestion.connector.oauth.OAuthStateStore;
import io.personalassistant.ingestion.connector.oauth.OAuthTokenService;
import io.personalassistant.ingestion.connector.oauth.OAuthTokens;
import io.personalassistant.testsupport.InMemoryChannelRepository;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConfig;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.StubOAuthProvider;
import io.personalassistant.testsupport.StubOAuthProviderRegistry;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Driven by a stub provider called "stub" rather than by Google, which is the point: if the flow can
 * be completed end to end without a single Google-specific string, adding the next OAuth application
 * really is one bean.
 */
class DefaultOAuthConnectServiceTest {

    private InMemoryConnectionRepository connections;
    private StubOAuthProvider provider;
    private OAuthStateStore states;
    private DefaultOAuthConnectService service;

    @BeforeEach
    void setUp() {
        connections = new InMemoryConnectionRepository();
        provider = new StubOAuthProvider("stub", SourceType.GMAIL);
        states = new OAuthStateStore();
        states.stateTtlSeconds = 600;

        OAuthClients clients = new OAuthClients(new StubConfig(Map.of(
                "app.oauth.stub.client-id", "client",
                "app.oauth.stub.client-secret", "secret")));
        StubConnector connector = new StubConnector(SourceType.GMAIL, List.of())
                .withRequiresConnection(true);
        DefaultConnectionService connectionService = new DefaultConnectionService(
                connections, new InMemoryKnowledgeRepository(), new InMemoryChannelRepository(),
                new CdiConnectionKindRegistry(List.of(), new SingleConnectorRegistry(connector)));

        service = new DefaultOAuthConnectService(
                new StubOAuthProviderRegistry(provider), clients, states,
                new OAuthTokenService(new StubOAuthProviderRegistry(provider), clients, connections),
                connectionService, connections);
        service.allowedOrigins = "http://localhost:8080,http://localhost:5173";
    }

    // ---- start -------------------------------------------------------------------------------

    @Test
    void buildsAConsentUrlForTheRequestedTypesScopes() {
        String url = service.start(start(null, "http://localhost:5173"));

        Assertions.assertTrue(url.startsWith("https://stub.test/consent?state="), url);
        AuthorizeRequest request = provider.authorizeCalls.get(0);
        Assertions.assertEquals("GMAIL", request.type());
        Assertions.assertEquals(java.util.Set.of("scope:gmail"), request.scopes());
    }

    @Test
    void sendsTheBrowserBackToTheOriginItStartedFrom() {
        service.start(start(null, "http://localhost:5173"));

        Assertions.assertEquals("http://localhost:5173/api/connections/oauth/stub/callback",
                provider.authorizeCalls.get(0).redirectUri());
    }

    @Test
    void fallsBackToTheCanonicalOriginWhenTheRequestCameFromAnUnknownOne() {
        service.start(start(null, "https://evil.example"));

        Assertions.assertEquals("http://localhost:8080/api/connections/oauth/stub/callback",
                provider.authorizeCalls.get(0).redirectUri(),
                "an unlisted origin must never become a redirect target");
    }

    @Test
    void rejectsAProviderThatDoesNotAuthenticateTheType() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.start(
                new OAuthConnectService.StartConnect("stub", "GOOGLE_DRIVE", null, null,
                        "http://localhost:8080")));
    }

    @Test
    void rejectsAnUnknownProviderId() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.start(
                new OAuthConnectService.StartConnect("nope", "GMAIL", null, null,
                        "http://localhost:8080")));
    }

    @Test
    void rejectsAConnectionOfADifferentType() {
        connections.save(TestData.connection("conn_drive", SourceType.GOOGLE_DRIVE, true, Map.of()));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.start(start("conn_drive", "http://localhost:8080")));
    }

    @Test
    void rejectsAnUnknownConnectionId() {
        Assertions.assertThrows(NoSuchElementException.class,
                () -> service.start(start("conn_missing", "http://localhost:8080")));
    }

    // ---- complete ----------------------------------------------------------------------------

    @Test
    void createsAConnectionCarryingTheMintedCredentials() {
        String state = stateFrom(service.start(start(null, "http://localhost:8080")));

        OAuthConnectService.Completed completed = service.complete("stub", state, "the-code");

        Connection created = completed.connection();
        Assertions.assertEquals("GMAIL", created.type());
        Assertions.assertEquals("access-1", created.auth().get("accessToken"));
        Assertions.assertEquals("refresh-1", created.auth().get("refreshToken"));
        Assertions.assertEquals("http://localhost:8080", completed.returnTo());
        Assertions.assertEquals(List.of("the-code"), provider.exchangedCodes);
    }

    @Test
    void recredentialsAnExistingConnectionAndClearsItsErrorState() {
        Connection broken = connections.save(TestData
                .connection("conn_1", SourceType.GMAIL, true, Map.of("refreshToken", "dead"))
                .withStatus(ConnectionStatus.ERROR, "The sign-in was rejected"));
        String state = stateFrom(service.start(start(broken.id(), "http://localhost:8080")));

        Connection after = service.complete("stub", state, "the-code").connection();

        Assertions.assertEquals("conn_1", after.id(), "no second connection was created");
        Assertions.assertEquals("access-1", after.auth().get("accessToken"));
        Assertions.assertEquals(ConnectionStatus.ACTIVE, after.status());
        Assertions.assertNull(after.lastError());
        Assertions.assertEquals(1, connections.store.size());
    }

    @Test
    void keepsTheStoredRefreshTokenWhenTheConsentReturnedNone() {
        Connection existing = connections.save(TestData.connection(
                "conn_1", SourceType.GMAIL, true, Map.of("refreshToken", "original")));
        provider.onExchange = () -> new OAuthTokens("access-1", null, StubOAuthProvider.far());
        String state = stateFrom(service.start(start(existing.id(), "http://localhost:8080")));

        Connection after = service.complete("stub", state, "the-code").connection();

        Assertions.assertEquals("original", after.auth().get("refreshToken"),
                "a consent without a refresh token must not wipe a working one");
    }

    @Test
    void refusesAReplayedState() {
        String state = stateFrom(service.start(start(null, "http://localhost:8080")));
        service.complete("stub", state, "the-code");

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.complete("stub", state, "the-code"));
    }

    @Test
    void refusesAnExpiredState() {
        // Negative rather than zero: a zero TTL only expires once the clock has actually moved, and
        // two Instant.now() calls can land in the same tick on a coarse clock.
        states.stateTtlSeconds = -1;
        String state = stateFrom(service.start(start(null, "http://localhost:8080")));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.complete("stub", state, "the-code"));
    }

    @Test
    void refusesAStateIssuedForADifferentProvider() {
        String state = stateFrom(service.start(start(null, "http://localhost:8080")));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.complete("other", state, "the-code"));
    }

    @Test
    void refusesACallbackWithNoCode() {
        String state = stateFrom(service.start(start(null, "http://localhost:8080")));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.complete("stub", state, null));
    }

    @Test
    void returnOriginIsReadableWithoutBurningTheState() {
        String state = stateFrom(service.start(start(null, "http://localhost:5173")));

        Assertions.assertEquals("http://localhost:5173", service.returnOriginFor(state));
        Assertions.assertNotNull(service.complete("stub", state, "the-code"),
                "peeking at a declined consent must leave the user able to retry");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static OAuthConnectService.StartConnect start(String connectionId, String origin) {
        return new OAuthConnectService.StartConnect("stub", "GMAIL", connectionId,
                "Test account", origin);
    }

    /** The stub echoes the state into its consent URL, which is the only place a caller can see it. */
    private static String stateFrom(String consentUrl) {
        int from = consentUrl.indexOf("state=") + "state=".length();
        int to = consentUrl.indexOf('&', from);
        return to < 0 ? consentUrl.substring(from) : consentUrl.substring(from, to);
    }
}
