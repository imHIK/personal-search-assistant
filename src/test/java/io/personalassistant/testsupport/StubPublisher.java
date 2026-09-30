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

public class StubPublisher implements Publisher {

    /** A target carrying this key is refused by {@link #validateTarget}. */
    public static final String INVALID = "invalid";

    private final ChannelType type;
    public final List<PublishMessage> sent = new ArrayList<>();
    public final List<String> references = new ArrayList<>();
    /** One entry per send; null when the publisher needs no account. */
    public final List<Connection> connections = new ArrayList<>();
    /** null = the publisher needs no account. */
    public String connectionType;
    public RuntimeException failure;
    public RuntimeException verifyFailure;
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
