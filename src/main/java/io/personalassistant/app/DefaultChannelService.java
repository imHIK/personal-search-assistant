package io.personalassistant.app;

import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.service.ChannelService;
import io.personalassistant.publishing.ChannelConnectionResolver;
import io.personalassistant.publishing.Publisher;
import io.personalassistant.publishing.PublisherRegistry;
import io.personalassistant.storage.repository.ChannelRepository;
import io.personalassistant.storage.repository.ConnectionRepository;
import io.personalassistant.storage.repository.DeliveryRepository;
import io.personalassistant.storage.repository.DigestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Channel lifecycle. Creation and edits are validated by the channel's own publisher, so a destination
 * that could never receive anything is refused when it is entered rather than when a digest first
 * tries to use it.
 */
@ApplicationScoped
public class DefaultChannelService implements ChannelService {

    private static final Logger LOG = Logger.getLogger(DefaultChannelService.class.getName());

    /** What a test send says. Fixed rather than configurable: its only job is to arrive. */
    static final PublishMessage SAMPLE = new PublishMessage(
            "Test message from Personal Search Assistant",
            "If you can read this, the channel works and queued messages will be delivered here.",
            List.of(), null);

    private final ChannelRepository channels;
    private final DeliveryRepository deliveries;
    private final ConnectionRepository connections;
    private final DigestRepository digests;
    private final PublisherRegistry publishers;
    private final ChannelConnectionResolver resolver;

    @Inject
    public DefaultChannelService(ChannelRepository channels, DeliveryRepository deliveries,
                                 ConnectionRepository connections, DigestRepository digests,
                                 PublisherRegistry publishers, ChannelConnectionResolver resolver) {
        this.channels = channels;
        this.deliveries = deliveries;
        this.connections = connections;
        this.digests = digests;
        this.publishers = publishers;
        this.resolver = resolver;
    }

    @Override
    public Channel create(NewChannel request) {
        requireName(request.name());
        Publisher publisher = publishers.get(request.type()); // no bean → IllegalArgumentException
        Map<String, Object> target = request.target() == null ? Map.of() : request.target();
        publisher.validateTarget(target);
        String connectionId = blankToNull(request.connectionId());
        checkConnection(publisher, connectionId);
        Instant now = Instant.now();
        return channels.insert(new Channel(Ids.channel(), request.name().trim(), request.type(), connectionId,
                target, request.enabled() == null || request.enabled(), ChannelStatus.ACTIVE, null, now, now));
    }

    @Override
    public Optional<Channel> get(String id) {
        return channels.findById(id);
    }

    @Override
    public List<Channel> list() {
        return channels.findAll();
    }

    @Override
    public Channel update(String id, ChannelEdit edit) {
        Channel current = require(id);
        Publisher publisher = publishers.get(current.type());
        String name = edit.name() == null ? current.name() : edit.name();
        requireName(name);
        Map<String, Object> target = edit.target() == null ? current.target() : edit.target();
        if (edit.target() != null) {
            publisher.validateTarget(target);
        }
        String connectionId = edit.connectionId() == null ? current.connectionId() : blankToNull(edit.connectionId());
        if (edit.connectionId() != null) {
            checkConnection(publisher, connectionId);
        }
        boolean enabled = edit.enabled() == null ? current.enabled() : edit.enabled();
        channels.updateEdits(id, name.trim(), connectionId, target, enabled, Instant.now());
        return require(id);
    }

    @Override
    public Channel test(String id) {
        Channel channel = require(id);
        ChannelStatus status;
        String error = null;
        try {
            Publisher publisher = publishers.get(channel.type());
            Connection connection = resolver.resolve(channel, publisher);
            publisher.verify(channel, connection);
            publisher.publish(channel, connection, SAMPLE, "test-" + Ids.delivery());
            status = ChannelStatus.ACTIVE;
        } catch (RuntimeException e) {
            // A failed check is a result to display, not an error to raise.
            LOG.log(Level.INFO, "Test send to channel " + id + " failed", e);
            status = ChannelStatus.ERROR;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        channels.updateStatus(id, status, error, Instant.now());
        return require(id);
    }

    @Override
    public void delete(String id) {
        require(id);
        // Refused rather than dropped from the digests: removing it silently would turn a digest's
        // emails off without anyone deciding to.
        List<Digest> sending = digests.findByChannelId(id);
        if (!sending.isEmpty()) {
            throw new IllegalStateException("Channel " + id + " is used by " + sending.size() + " digest(s) ("
                    + sending.stream().map(Digest::name).collect(Collectors.joining(", "))
                    + "); remove it from them first");
        }
        // Deliveries first: a channel row that outlives its deliveries is harmless, the reverse leaves
        // PENDING rows nothing will ever claim.
        deliveries.deleteByChannel(id);
        channels.delete(id);
    }

    /**
     * An explicitly named account must exist and be of the type the publisher sends through. A null id
     * is not checked: it means "the default", which may legitimately be connected later.
     */
    private void checkConnection(Publisher publisher, String connectionId) {
        if (connectionId == null) {
            return;
        }
        String type = publisher.connectionType().orElseThrow(() -> new IllegalArgumentException(
                "A " + publisher.type() + " channel does not send through an account; remove connectionId"));
        Connection connection = connections.findById(connectionId).orElseThrow(() ->
                new IllegalArgumentException("No connection with id " + connectionId));
        if (!connection.type().equals(type)) {
            throw new IllegalArgumentException("Connection " + connectionId + " is a " + connection.type()
                    + " account; a " + publisher.type() + " channel sends through " + type);
        }
    }

    private Channel require(String id) {
        return channels.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No channel with id " + id));
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
