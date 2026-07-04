package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.NoSuchElementException;

/**
 * Default {@link ConnectionResolver} over the {@link ConnectionRepository}: an explicit
 * {@code connectionId} wins; otherwise the type's default connection is used. Both misses throw a
 * clear {@link NoSuchElementException} so knowledge activation lands in {@code ERROR} with an
 * actionable reason ("no default GMAIL connection — create one") rather than a null-pointer later.
 */
@ApplicationScoped
public class DefaultConnectionResolver implements ConnectionResolver {

    private final ConnectionRepository connections;

    @Inject
    public DefaultConnectionResolver(ConnectionRepository connections) {
        this.connections = connections;
    }

    @Override
    public Connection resolve(Knowledge knowledge) {
        SourceType type = knowledge.connectorDetails().type();
        String connectionId = knowledge.connectorDetails().connectionId();

        Connection connection = connectionId != null && !connectionId.isBlank()
                ? connections.findById(connectionId).orElseThrow(() -> new NoSuchElementException(
                        "Knowledge " + knowledge.id() + " references unknown connection " + connectionId))
                : connections.findDefault(type).orElseThrow(() -> new NoSuchElementException(
                        "No default " + type + " connection configured; create one or set "
                                + "connectorDetails.connectionId on the knowledge"));

        if (connection.type() != type) {
            throw new NoSuchElementException("Connection " + connection.id() + " is a " + connection.type()
                    + " connection but knowledge " + knowledge.id() + " is " + type);
        }
        if (connection.status() == ConnectionStatus.DISABLED) {
            throw new NoSuchElementException("Connection " + connection.id() + " is DISABLED");
        }
        return connection;
    }
}
