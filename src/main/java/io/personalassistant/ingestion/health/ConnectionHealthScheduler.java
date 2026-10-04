package io.personalassistant.ingestion.health;

import io.personalassistant.connection.ConnectionKindRegistry;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.service.ConnectionService;
import io.personalassistant.storage.repository.ConnectionRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Credentials are otherwise verified only at create and on an auth or config edit. The result is recorded on the
 * connection, and IngestionJob skips knowledges whose connection is in ERROR; nothing is paused, so a
 * connection that works again resumes on its own.
 */
@ApplicationScoped
public class ConnectionHealthScheduler {

    private static final Logger LOG = Logger.getLogger(ConnectionHealthScheduler.class.getName());

    private final ConnectionRepository connections;
    private final ConnectionService service;
    private final ConnectionKindRegistry kinds;

    @Inject
    public ConnectionHealthScheduler(ConnectionRepository connections, ConnectionService service,
                                     ConnectionKindRegistry kinds) {
        this.connections = connections;
        this.service = service;
        this.kinds = kinds;
    }

    @Scheduled(every = "{app.connections.health-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        List<Connection> all;
        try {
            all = connections.findAll();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not list connections to health-check", e);
            return;
        }
        for (Connection connection : all) {
            check(connection);
        }
    }

    void sweep() {
        tick();
    }

    private void check(Connection connection) {
        // DISABLED is an operator decision; the service refuses to re-activate it anyway.
        if (connection.status() == ConnectionStatus.DISABLED
                || !kinds.supports(connection.type())) {
            return;
        }
        ConnectionStatus before = connection.status();
        try {
            Connection after = service.test(connection.id());
            if (after.status() != before) {
                // Only transitions are logged.
                LOG.info("Connection " + connection.id() + " (" + connection.type() + ") "
                        + before + " -> " + after.status()
                        + (after.lastError() == null ? "" : ": " + after.lastError()));
            }
        } catch (RuntimeException e) {
            // test() absorbs credential failures, so this is something else; one bad connection must not stop
            // the sweep.
            LOG.log(Level.WARNING, "Health check failed for connection " + connection.id(), e);
        }
    }
}
