package io.personalassistant.app;

import io.personalassistant.common.id.Ids;
import io.personalassistant.connection.ConnectionKind;
import io.personalassistant.connection.ConnectionKindRegistry;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.service.ConnectionService;
import io.personalassistant.storage.repository.ChannelRepository;
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
 * Default connection lifecycle orchestration. Creation verifies credentials via the type's ConnectionKind
 * (so a bad token fails fast at connect time, not on the first grab), then persists and assigns the
 * per-type default. Deletion enforces referential integrity against bound knowledges and keeps the
 * per-type default well-formed by promoting a survivor.
 */
@ApplicationScoped
public class DefaultConnectionService implements ConnectionService {

    private static final Logger LOG = Logger.getLogger(DefaultConnectionService.class.getName());

    private final ConnectionRepository connections;
    private final KnowledgeRepository knowledge;
    private final ChannelRepository channels;
    private final ConnectionKindRegistry kinds;

    @Inject
    public DefaultConnectionService(ConnectionRepository connections, KnowledgeRepository knowledge,
                                    ChannelRepository channels, ConnectionKindRegistry kinds) {
        this.connections = connections;
        this.knowledge = knowledge;
        this.channels = channels;
        this.kinds = kinds;
    }

    @Override
    public Connection create(NewConnection request) {
        // Unknown type, or a connector that needs no connection → IllegalArgumentException → 400.
        ConnectionKind kind = kinds.get(request.type());

        Instant now = Instant.now();
        Connection draft = new Connection(Ids.connection(), request.name(), request.type(),
                request.auth(), request.config(), request.rateLimit(), false,
                ConnectionStatus.ACTIVE, null, now, now);

        verify(kind, draft); // bad credentials → throws → 400, nothing persisted

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
    public List<Connection> listByType(String type) {
        return connections.findByType(type);
    }

    @Override
    public Connection update(String id, ConnectionEdit edit) {
        Connection current = require(id);
        Connection edited = current.withEdits(
                edit.name() != null ? edit.name() : current.name(),
                edit.auth() != null ? edit.auth() : current.auth(),
                edit.config() != null ? edit.config() : current.config(),
                edit.rateLimit() != null ? edit.rateLimit() : current.rateLimit(),
                Instant.now());

        if (edit.auth() != null && !edit.auth().equals(current.auth())) {
            verify(kinds.get(current.type()), edited); // re-verify changed creds
            edited = edited.withStatus(ConnectionStatus.ACTIVE, null);
        }
        return connections.save(edited);
    }

    @Override
    public Connection test(String id) {
        Connection current = require(id);
        if (!kinds.supports(current.type())) {
            return connections.save(current.withStatus(ConnectionStatus.ERROR,
                    "Nothing installed uses " + current.type() + " connections"));
        }
        try {
            kinds.get(current.type()).verify(current);
            // DISABLED is an operator decision, not a credential state — a passing check must not
            // silently re-enable a connection someone turned off.
            if (current.status() == ConnectionStatus.DISABLED) {
                return current;
            }
            return connections.save(current.withStatus(ConnectionStatus.ACTIVE, null));
        } catch (RuntimeException e) {
            String reason = reason(e);
            LOG.warning("Connection " + id + " (" + current.type() + ") failed verification: " + reason);
            return connections.save(current.withStatus(ConnectionStatus.ERROR, reason));
        }
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
        int sending = channels.findByConnectionId(id).size();
        if (sending > 0) {
            throw new IllegalStateException("Connection " + id + " is used by " + sending
                    + " channel(s); repoint or delete them first");
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

    /**
     * Run the connector's credential check, funnelling every failure into
     * {@link IllegalArgumentException} so the resource maps it to a 400 that carries the reason.
     *
     * <p>Connectors raise their own transport exceptions — {@code GoogleApiException},
     * {@code AtsApiException}, {@code RateLimitedException} — and the resource knows none of them, so an
     * expired refresh token used to escape as a bare 500 whose body said nothing and left the console
     * with no cause to display. The message is worded like {@link #test}'s {@code lastError} on purpose:
     * a rejected save and a failed re-test should read identically.
     */
    private void verify(ConnectionKind kind, Connection connection) {
        try {
            kind.verify(connection);
        } catch (IllegalArgumentException e) {
            throw e; // already the shape the resource turns into a 400
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    connection.type() + " rejected the credentials: " + reason(e), e);
        }
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private Connection require(String id) {
        return connections.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No connection with id " + id));
    }
}
