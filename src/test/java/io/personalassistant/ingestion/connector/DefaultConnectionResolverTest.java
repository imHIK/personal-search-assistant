package io.personalassistant.ingestion.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultConnectionResolverTest {

    private InMemoryConnectionRepository connections;
    private DefaultConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        connections = new InMemoryConnectionRepository();
        resolver = new DefaultConnectionResolver(connections);
    }

    private Knowledge knowledge(String connectionId) {
        return TestData.knowledgeWithConnection("kn1", SourceType.GMAIL, connectionId, Instant.now(), Map.of());
    }

    @Test
    void resolvesTheTypeDefaultWhenNoConnectionNamed() {
        connections.save(TestData.connection("conn_default", SourceType.GMAIL, true, Map.of()));
        assertEquals("conn_default", resolver.resolve(knowledge(null)).id());
    }

    @Test
    void resolvesTheNamedConnectionOverTheDefault() {
        connections.save(TestData.connection("conn_default", SourceType.GMAIL, true, Map.of()));
        connections.save(TestData.connection("conn_named", SourceType.GMAIL, false, Map.of()));
        assertEquals("conn_named", resolver.resolve(knowledge("conn_named")).id());
    }

    @Test
    void throwsWhenNamedConnectionIsUnknown() {
        assertThrows(NoSuchElementException.class, () -> resolver.resolve(knowledge("conn_missing")));
    }

    @Test
    void throwsWhenNoDefaultConfiguredForType() {
        assertThrows(NoSuchElementException.class, () -> resolver.resolve(knowledge(null)));
    }

    @Test
    void throwsWhenConnectionTypeMismatchesKnowledge() {
        connections.save(TestData.connection("conn_drive", SourceType.GOOGLE_DRIVE, false, Map.of()));
        assertThrows(NoSuchElementException.class, () -> resolver.resolve(knowledge("conn_drive")));
    }

    @Test
    void throwsWhenConnectionIsDisabled() {
        Connection disabled = TestData.connection("conn_off", SourceType.GMAIL, false, Map.of())
                .withStatus(ConnectionStatus.DISABLED, null);
        connections.save(disabled);
        assertThrows(NoSuchElementException.class, () -> resolver.resolve(knowledge("conn_off")));
    }
}
