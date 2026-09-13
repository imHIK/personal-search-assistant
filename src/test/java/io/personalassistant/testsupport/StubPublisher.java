package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.publishing.PublishReceipt;
import io.personalassistant.publishing.Publisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A scriptable {@link Publisher}: records what it was asked to send and through which account, and can be
 * made to need an account, refuse a target, fail verification, or throw on send.
 */
public class StubPublisher implements Publisher {

    /** A target carrying this key is refused by {@link #validateTarget}. */
    public static final String INVALID = "invalid";

    private final ChannelType type;
    public final List<PublishMessage> sent = new ArrayList<>();
    public final List<String> references = new ArrayList<>();
    /** The account each send went through, in order; null entries for a publisher that needs none. */
    public final List<Connection> connections = new ArrayList<>();
    /** The connection type sends go through; null means this publisher needs no account. */
    public String connectionType;
    /** Thrown by every send while set. */
    public RuntimeException failure;
    /** Thrown by {@link #verify} while set. */
    public RuntimeException verifyFailure;
    /** Run inside a send, before it returns — e.g. to simulate losing the lease mid-send. */
    public Runnable duringPublish;

    public StubPublisher() {
        this(ChannelType.EMAIL);
    }

    public StubPublisher(ChannelType type) {
        this.type = type;
    }

    @Override
    public ChannelType type() {
        return type;
    }

    @Override
    public Optional<String> connectionType() {
        return Optional.ofNullable(connectionType);
    }

    @Override
    public void validateTarget(Map<String, Object> target) {
        if (target.containsKey(INVALID)) {
            throw new IllegalArgumentException("target refused");
        }
    }

    @Override
    public void verify(Channel channel, Connection connection) {
        if (verifyFailure != null) {
            throw verifyFailure;
        }
    }

    @Override
    public PublishReceipt publish(Channel channel, Connection connection, PublishMessage message, String reference) {
        references.add(reference);
        connections.add(connection);
        if (duringPublish != null) {
            duringPublish.run();
        }
        if (failure != null) {
            throw failure;
        }
        sent.add(message);
        return new PublishReceipt("msg-" + reference);
    }
}
