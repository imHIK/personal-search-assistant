package io.personalassistant.ingestion.health;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.service.ConnectionService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.storage.repository.ConnectionRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically re-checks every stored connection's credentials.
 *
 * <p>Credentials are otherwise only verified at create and on an auth edit, so a token that expires
 * afterwards leaves its connection reading {@code ACTIVE} while every sync fails. That is not
 * hypothetical: an expired Google refresh token produces an {@code invalid_grant} on every ingestion
 * tick — the failures scroll past in the log, the connection still looks healthy in the console, and
 * the user's first real signal is that their data has quietly stopped updating.
 *
 * <p>The result is recorded on the connection, which {@code IngestionJob} then honours by skipping
 * knowledges whose connection is in {@code ERROR}. Nothing is paused and no knowledge state is
 * touched: the skip is derived from the connection's status, so a connection that starts working
 * again resumes sync on its own with no user action — and no risk of un-pausing something the user
 * deliberately paused.
 */
@ApplicationScoped
public class ConnectionHealthScheduler {

    private static final Logger LOG = Logger.getLogger(ConnectionHealthScheduler.class.getName());

    private final ConnectionRepository connections;
    private final ConnectionService service;
    private final ConnectorRegistry connectors;

    @Inject
    public ConnectionHealthScheduler(ConnectionRepository connections, ConnectionService service,
                                     ConnectorRegistry connectors) {
        this.connections = connections;
        this.service = service;
        this.connectors = connectors;
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

    /** Visible for tests that drive a sweep directly rather than through the scheduler. */
    void sweep() {
        tick();
    }

    private void check(Connection connection) {
        // DISABLED is an operator decision; re-checking it would only produce noise, and the service
        // deliberately refuses to re-activate it anyway.
        if (connection.status() == ConnectionStatus.DISABLED
                || !connectors.supports(connection.type())) {
            return;
        }
        ConnectionStatus before = connection.status();
        try {
            Connection after = service.test(connection.id());
            if (after.status() != before) {
                // Only the transitions are worth a line. Logging every healthy check would bury them.
                LOG.info("Connection " + connection.id() + " (" + connection.type() + ") "
                        + before + " -> " + after.status()
                        + (after.lastError() == null ? "" : ": " + after.lastError()));
            }
        } catch (RuntimeException e) {
            // test() already absorbs credential failures, so reaching here means something else broke.
            // One bad connection must not stop the sweep.
            LOG.log(Level.WARNING, "Health check failed for connection " + connection.id(), e);
        }
    }
}
