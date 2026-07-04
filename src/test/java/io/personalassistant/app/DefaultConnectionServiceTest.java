package io.personalassistant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
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
                Map.of("token", name), Map.of(), makeDefault));
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
        service.update(c.id(), new ConnectionEdit("Work Slack", Map.of("token", "rotated"), null));
        assertEquals(before + 1, connector.verifyConnectionCalls, "changed auth re-verifies");
        assertEquals("Work Slack", connections.findById(c.id()).orElseThrow().name());
    }

    @Test
    void updateUnknownConnectionThrows() {
        assertThrows(NoSuchElementException.class,
                () -> service.update("conn_missing", new ConnectionEdit("x", null, null)));
    }
}
