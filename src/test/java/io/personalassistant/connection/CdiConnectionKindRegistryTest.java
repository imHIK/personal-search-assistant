package io.personalassistant.connection;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.SingleConnectorRegistry;
import io.personalassistant.testsupport.StubConnector;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Connection types come from connectors that need credentials and from standalone kinds, never both. */
class CdiConnectionKindRegistryTest {

    private static ConnectionKind kind(String id) {
        return new ConnectionKind() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void verify(Connection connection) {
            }
        };
    }

    @Test
    void aConnectorThatNeedsCredentialsIsAKindNamedAfterItsSourceType() {
        StubConnector slack = new StubConnector(SourceType.SLACK, List.of()).withRequiresConnection(true);

        CdiConnectionKindRegistry registry = new CdiConnectionKindRegistry(List.of(), new SingleConnectorRegistry(slack));

        Assertions.assertTrue(registry.supports("SLACK"));
        Assertions.assertEquals("SLACK", registry.get("SLACK").id());
    }

    @Test
    void aConnectorThatNeedsNoCredentialsIsNotAKind() {
        StubConnector localFs = new StubConnector(SourceType.LOCAL_FS, List.of());

        CdiConnectionKindRegistry registry =
                new CdiConnectionKindRegistry(List.of(), new SingleConnectorRegistry(localFs));

        Assertions.assertFalse(registry.supports("LOCAL_FS"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.get("LOCAL_FS"));
    }

    @Test
    void aStandaloneKindNeedsNoConnector() {
        StubConnector slack = new StubConnector(SourceType.SLACK, List.of()).withRequiresConnection(true);

        CdiConnectionKindRegistry registry =
                new CdiConnectionKindRegistry(List.of(kind("GMAIL_SEND")), new SingleConnectorRegistry(slack));

        Assertions.assertTrue(registry.supports("GMAIL_SEND"));
        Assertions.assertEquals(java.util.Set.of("SLACK", "GMAIL_SEND"), registry.types());
    }

    @Test
    void twoClaimantsForOneTypeFailStartup() {
        StubConnector slack = new StubConnector(SourceType.SLACK, List.of()).withRequiresConnection(true);

        Assertions.assertThrows(IllegalStateException.class,
                () -> new CdiConnectionKindRegistry(List.of(kind("SLACK")), new SingleConnectorRegistry(slack)));
    }
}
