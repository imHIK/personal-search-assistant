package io.personalassistant.app;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.ConnectionService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.storage.repository.ConnectionRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Default connection lifecycle orchestration. Creation verifies the credentials against the connector
 * (so a bad token fails fast at connect time, not on the first grab), then persists and assigns the
 * per-type default. Deletion enforces referential integrity against bound knowledges and keeps the
 * per-type default well-formed by promoting a survivor.
 */
@ApplicationScoped
public class DefaultConnectionService implements ConnectionService {

    private static final Logger LOG = Logger.getLogger(DefaultConnectionService.class.getName());

    private final ConnectionRepository connections;
    private final KnowledgeRepository knowledge;
    private final ConnectorRegistry connectors;

    @Inject
    public DefaultConnectionService(ConnectionRepository connections, KnowledgeRepository knowledge,
                                    ConnectorRegistry connectors) {
        this.connections = connections;
        this.knowledge = knowledge;
        this.connectors = connectors;
    }

    @Override
    public Connection create(NewConnection request) {
        SourceConnector connector = connectors.get(request.type()); // unknown type → IllegalArgumentException
        if (!connector.requiresConnection()) {
            throw new IllegalArgumentException(request.type() + " does not use connections");
        }

        Instant now = Instant.now();
        Connection draft = new Connection(Ids.connection(), request.name(), request.type(),
                request.auth(), request.config(), false, ConnectionStatus.ACTIVE, null, now, now);

        connector.verifyConnection(draft); // bad credentials → throws → 400, nothing persisted

        boolean makeDefault = request.makeDefault()
                || connections.findDefault(request.type()).isEmpty(); // first-of-type is the default
        if (makeDefault) {
            connections.clearDefault(request.type());
        }
        Connection saved = connections.save(draft.asDefault(makeDefault));
        LOG.info("Created connection " + saved.id() + " (" + saved.type()
                + (saved.isDefault() ? ", default" : "") + ")");
        return saved;
    }

    @Override
    public Optional<Connection> get(String id) {
        return connections.findById(id);
    }

    @Override
    public List<Connection> list() {
        return connections.findAll();
    }

    @Override
    public List<Connection> listByType(SourceType type) {
        return connections.findByType(type);
    }

    @Override
    public Connection update(String id, ConnectionEdit edit) {
        Connection current = require(id);
        Connection edited = current.withEdits(
                edit.name() != null ? edit.name() : current.name(),
                edit.auth() != null ? edit.auth() : current.auth(),
                edit.config() != null ? edit.config() : current.config(),
                Instant.now());

        if (edit.auth() != null && !edit.auth().equals(current.auth())) {
            connectors.get(current.type()).verifyConnection(edited); // re-verify changed creds
            edited = edited.withStatus(ConnectionStatus.ACTIVE, null);
        }
        return connections.save(edited);
    }

    @Override
    public Connection setDefault(String id) {
        Connection current = require(id);
        connections.clearDefault(current.type());
        return connections.save(current.asDefault(true));
    }

    @Override
    public void delete(String id) {
        Connection current = require(id);
        List<Knowledge> bound = knowledge.findByConnectionId(id);
        if (!bound.isEmpty()) {
            throw new IllegalStateException("Connection " + id + " is in use by " + bound.size()
                    + " knowledge(s); repoint or delete them first");
        }
        connections.delete(id);

        // Keep the per-type default well-formed: if we removed the default, promote the oldest survivor.
        if (current.isDefault()) {
            connections.findByType(current.type()).stream()
                    .min(Comparator.comparing(Connection::createdAt))
                    .ifPresent(next -> {
                        connections.clearDefault(current.type());
                        connections.save(next.asDefault(true));
                        LOG.info("Promoted connection " + next.id() + " to default for " + current.type());
                    });
        }
        LOG.info("Deleted connection " + id);
    }

    private Connection require(String id) {
        return connections.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No connection with id " + id));
    }
}
