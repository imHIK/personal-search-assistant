package io.personalassistant.publishing;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.storage.repository.ConnectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The channel's own connectionId, else the default connection of the type its publisher declares; one of the
 * wrong type is refused rather than used.
 */
@ApplicationScoped
public class ChannelConnectionResolver {

    private final ConnectionRepository connections;

    @Inject
    public ChannelConnectionResolver(ConnectionRepository connections) {
        this.connections = connections;
    }

    /**
     * @return null when the publisher needs no account
     * @throws NoSuchElementException if it needs one and none resolves, or the named one is of another type
     */
    public Connection resolve(Channel channel, Publisher publisher) {
        Optional<String> type = publisher.connectionType();
        if (type.isEmpty()) {
            return null;
        }
        String id = channel.connectionId();
        Connection connection = id != null
                ? connections.findById(id).orElseThrow(() -> new NoSuchElementException(
                        "The account this channel sends through (" + id + ") no longer exists"))
                : connections.findDefault(type.get()).orElseThrow(() -> new NoSuchElementException(
                        "No " + type.get() + " account is connected — connect one on the Accounts page"));
        if (!connection.type().equals(type.get())) {
            throw new NoSuchElementException("Connection " + connection.id() + " is a " + connection.type()
                    + " account, but this channel sends through " + type.get());
        }
        return connection;
    }

    /** ERROR waits for a reconnect and DISABLED for an operator; either way sending would fail. */
    public static boolean usable(Connection connection) {
        return connection == null || connection.status() == ConnectionStatus.ACTIVE;
    }
}
