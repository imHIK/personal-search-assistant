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
 * Every connection type something is installed to use, from two sources:
 *
 * <ul>
 *   <li><strong>connectors</strong> — each {@link SourceConnector} that {@code requiresConnection()} is a
 *       kind named after its {@code SourceType}, verified by the connector's own
 *       {@code verifyConnection}. Derived rather than declared, so a connector still needs nothing
 *       beyond its bean and its enum constant, and existing {@code GMAIL} / {@code GOOGLE_DRIVE}
 *       connections keep resolving exactly as before;</li>
 *   <li><strong>{@link ConnectionKind} beans</strong> — for accounts that nothing in the knowledge flow
 *       uses, such as the email publisher's send-only Gmail account.</li>
 * </ul>
 *
 * Two claimants for one type is a startup failure: which one verified a connection would otherwise
 * depend on bean iteration order.
 */
@ApplicationScoped
public class CdiConnectionKindRegistry implements ConnectionKindRegistry {

    private final Map<String, ConnectionKind> byType = new LinkedHashMap<>();

    @Inject
    public CdiConnectionKindRegistry(Instance<ConnectionKind> kinds, ConnectorRegistry connectors) {
        this((Iterable<ConnectionKind>) kinds, connectors);
    }

    /** Hand-wired form, for tests. */
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

    /** A connector's account, verified by the connector itself. */
    private record ConnectorKind(String id, SourceConnector connector) implements ConnectionKind {

        @Override
        public void verify(Connection connection) {
            connector.verifyConnection(connection);
        }
    }
}
