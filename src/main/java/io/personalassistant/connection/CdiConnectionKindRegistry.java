package io.personalassistant.connection;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kinds come from connectors that require a connection (named after their SourceType) and from ConnectionKind
 * beans such as GMAIL_SEND. Two claimants for one type fail startup.
 */
@ApplicationScoped
public class CdiConnectionKindRegistry implements ConnectionKindRegistry {

    private final Map<String, ConnectionKind> byType = new LinkedHashMap<>();

    @Inject
    public CdiConnectionKindRegistry(Instance<ConnectionKind> kinds, ConnectorRegistry connectors) {
        this((Iterable<ConnectionKind>) kinds, connectors);
    }

    public CdiConnectionKindRegistry(Iterable<ConnectionKind> kinds, ConnectorRegistry connectors) {
        for (SourceType type : SourceType.values()) {
            if (connectors.supports(type) && connectors.get(type).requiresConnection()) {
                register(new ConnectorKind(type.name(), connectors.get(type)));
            }
        }
        for (ConnectionKind kind : kinds) {
            register(kind);
        }
    }

    private void register(ConnectionKind kind) {
        ConnectionKind clash = byType.putIfAbsent(kind.id(), kind);
        if (clash != null) {
            throw new IllegalStateException("Two connection kinds claim the type " + kind.id());
        }
    }

    @Override
    public ConnectionKind get(String type) {
        ConnectionKind kind = type == null ? null : byType.get(type);
        if (kind == null) {
            throw new IllegalArgumentException(type + " does not use connections");
        }
        return kind;
    }

    @Override
    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }

    @Override
    public Set<String> types() {
        return Set.copyOf(byType.keySet());
    }

    private record ConnectorKind(String id, SourceConnector connector) implements ConnectionKind {

        @Override
        public void verify(Connection connection) {
            connector.verifyConnection(connection);
        }
    }
}
