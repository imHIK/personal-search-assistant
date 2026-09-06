package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitRules;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.ConnectionService.ConnectionEdit;
import io.personalassistant.domain.service.ConnectionService.NewConnection;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.InMemoryKnowledgeRepository;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConnectionServiceTest {

    private InMemoryConnectionRepository connections;
    private InMemoryKnowledgeRepository knowledge;
    private StubConnector connector;
    private DefaultConnectionService service;

    @BeforeEach
    void setUp() {
        connections = new InMemoryConnectionRepository();
        knowledge = new InMemoryKnowledgeRepository();
        connector = new StubConnector(SourceType.SLACK, List.of()).withRequiresConnection(true);
        service = new DefaultConnectionService(connections, knowledge, new SingleConnectorRegistry(connector));
    }

    private Connection create(String name, boolean makeDefault) {
        return service.create(new NewConnection(name, SourceType.SLACK,
                Map.of("token", name), Map.of(), null, makeDefault));
    }

    @Test
    void createVerifiesCredentialsAndMakesFirstConnectionDefault() {
        Connection c = create("work", false);
        assertTrue(c.isDefault(), "first connection of a type is the default");
        assertEquals(1, connector.verifyConnectionCalls, "credentials are verified at create time");
    }

    @Test
    void secondConnectionIsNotDefaultUnlessRequested() {
        Connection first = create("work", false);
        Connection second = create("personal", false);
        assertTrue(connections.findById(first.id()).orElseThrow().isDefault());
        assertFalse(second.isDefault());
        assertEquals(first.id(), connections.findDefault(SourceType.SLACK).orElseThrow().id());
    }

    @Test
    void makeDefaultDemotesThePreviousDefault() {
        Connection first = create("work", false);
        Connection second = create("personal", true);
        assertTrue(second.isDefault());
        assertFalse(connections.findById(first.id()).orElseThrow().isDefault(), "old default demoted");
    }

    @Test
    void createRejectsBadCredentialsAndPersistsNothing() {
        connector.failVerifyConnectionWith(new IllegalArgumentException("revoked token"));
        assertThrows(IllegalArgumentException.class, () -> create("bad", false));
        assertTrue(connections.findAll().isEmpty(), "a failed verification saves nothing");
    }

    @Test
    void createTranslatesAConnectorsOwnExceptionIntoTheBadRequestShape() {
        // A connector raises its own transport type (GoogleApiException, AtsApiException, ...). The
        // resource only maps IllegalArgumentException, so anything else used to escape as a bare 500
        // with no body and the console had nothing to show the user.
        connector.failVerifyConnectionWith(new IllegalStateException("token refresh failed: invalid_grant"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> create("bad", false));

        assertTrue(thrown.getMessage().contains("invalid_grant"), "the reason survives: " + thrown.getMessage());
        assertTrue(connections.findAll().isEmpty(), "a failed verification saves nothing");
    }

    @Test
    void updateTranslatesAConnectorsOwnExceptionIntoTheBadRequestShape() {
        Connection c = create("work", false);
        connector.failVerifyConnectionWith(new IllegalStateException("token refresh failed: invalid_grant"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.update(c.id(), new ConnectionEdit(null, Map.of("token", "rotated"), null, null)));

        assertTrue(thrown.getMessage().contains("invalid_grant"), "the reason survives: " + thrown.getMessage());
        assertEquals(Map.of("token", "work"), connections.findById(c.id()).orElseThrow().auth(),
                "a rejected edit is not persisted");
    }

    @Test
    void createRejectsConnectorThatNeedsNoConnection() {
        connector.withRequiresConnection(false);
        assertThrows(IllegalArgumentException.class, () -> create("pointless", false));
    }

    @Test
    void deleteIsBlockedWhileAKnowledgeStillBindsTheConnection() {
        Connection c = create("work", false);
        knowledge.save(TestData.knowledgeWithConnection("kn1", SourceType.SLACK, c.id(),
                java.time.Instant.now(), Map.of()));
        assertThrows(IllegalStateException.class, () -> service.delete(c.id()));
        assertTrue(connections.findById(c.id()).isPresent(), "in-use connection is not deleted");
    }

    @Test
    void deletingTheDefaultPromotesAnotherConnection() {
        Connection first = create("work", false);   // default
        Connection second = create("personal", false);
        service.delete(first.id());
        assertEquals(second.id(), connections.findDefault(SourceType.SLACK).orElseThrow().id(),
                "the surviving connection is promoted to default");
    }

    @Test
    void setDefaultRepointsTheTypeDefault() {
        Connection first = create("work", false);
        Connection second = create("personal", false);
        service.setDefault(second.id());
        assertTrue(connections.findById(second.id()).orElseThrow().isDefault());
        assertFalse(connections.findById(first.id()).orElseThrow().isDefault());
    }

    @Test
    void updateReverifiesWhenAuthChanges() {
        Connection c = create("work", false);
        int before = connector.verifyConnectionCalls;
        service.update(c.id(), new ConnectionEdit("Work Slack", Map.of("token", "rotated"), null, null));
        assertEquals(before + 1, connector.verifyConnectionCalls, "changed auth re-verifies");
        assertEquals("Work Slack", connections.findById(c.id()).orElseThrow().name());
    }

    // ---- rate limits -------------------------------------------------------------------------

    @Test
    void anAccountsRateLimitRoundTrips() {
        Connection c = service.create(new NewConnection("work", SourceType.SLACK, Map.of("token", "t"),
                Map.of(), RateLimitRules.parse("10/1s,500/1m"), false));

        RateLimitPolicy stored = connections.findById(c.id()).orElseThrow().rateLimit();
        assertEquals(2, stored.rules().size());
        assertEquals(10, stored.rules().get(0).permits());
        assertEquals(60, stored.rules().get(1).windowSeconds());
    }

    @Test
    void patchingOnlyTheRateLimitLeavesCredentialsAlone() {
        Connection c = create("work", false);
        int before = connector.verifyConnectionCalls;

        service.update(c.id(), new ConnectionEdit(null, null, null, RateLimitRules.parse("5/1m")));

        Connection stored = connections.findById(c.id()).orElseThrow();
        assertEquals(1, stored.rateLimit().rules().size());
        assertEquals(Map.of("token", "work"), stored.auth(), "auth is untouched");
        assertEquals(before, connector.verifyConnectionCalls, "an unchanged auth blob is not re-verified");
    }

    /**
     * Null already means "leave unchanged" on a PATCH, so removal has to be said explicitly. Without
     * this an account's limit could be set but never taken off again.
     */
    @Test
    void anEmptyRuleListClearsALimitWhileNullLeavesItAlone() {
        Connection c = service.create(new NewConnection("work", SourceType.SLACK, Map.of("token", "t"),
                Map.of(), RateLimitRules.parse("10/1s"), false));

        service.update(c.id(), new ConnectionEdit("Renamed", null, null, null));
        assertEquals(1, connections.findById(c.id()).orElseThrow().rateLimit().rules().size(),
                "a null rateLimit leaves the existing one in place");

        service.update(c.id(), new ConnectionEdit(null, null, null, RateLimitPolicy.UNLIMITED));
        assertTrue(connections.findById(c.id()).orElseThrow().rateLimit().isUnlimited(),
                "an explicit empty rule list removes the limit");
    }

    @Test
    void updateUnknownConnectionThrows() {
        assertThrows(NoSuchElementException.class,
                () -> service.update("conn_missing", new ConnectionEdit("x", null, null, null)));
    }

    // ---- test() -------------------------------------------------------------------------------

    @Test
    void testMarksAWorkingConnectionActive() {
        Connection created = create("Work", true);
        connections.save(created.withStatus(ConnectionStatus.ERROR, "stale failure"));

        Connection checked = service.test(created.id());

        Assertions.assertEquals(ConnectionStatus.ACTIVE, checked.status());
        Assertions.assertNull(checked.lastError(), "a passing check must clear the stale reason");
    }

    @Test
    void testRecordsBadCredentialsRatherThanThrowing() {
        // A failed check is a result to display, not an error — and the scheduled sweep has to carry
        // on to the next connection.
        Connection created = create("Work", true);
        connector.failVerifyConnectionWith(new IllegalStateException("invalid_grant"));

        Connection checked = Assertions.assertDoesNotThrow(() -> service.test(created.id()));

        Assertions.assertEquals(ConnectionStatus.ERROR, checked.status());
        Assertions.assertEquals("invalid_grant", checked.lastError());
        Assertions.assertEquals(ConnectionStatus.ERROR,
                connections.findById(created.id()).orElseThrow().status(), "and it is persisted");
    }

    @Test
    void testLeavesADisabledConnectionDisabled() {
        // DISABLED is an operator decision; a passing credential check must not silently re-enable it.
        Connection created = create("Work", true);
        connections.save(created.withStatus(ConnectionStatus.DISABLED, null));

        Assertions.assertEquals(ConnectionStatus.DISABLED, service.test(created.id()).status());
    }

    @Test
    void testReportsAnUnknownConnection() {
        Assertions.assertThrows(NoSuchElementException.class, () -> service.test("conn_nope"));
    }
}
