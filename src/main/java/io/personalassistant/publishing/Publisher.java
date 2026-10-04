package io.personalassistant.publishing;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelType;
import java.util.Map;
import java.util.Optional;

/**
 * One per platform, discovered by CDI. A publisher owns its target's shape, the rendering and the failure
 * classification; the outbox, retries, leases, channel status and account resolution are the framework's.
 */
public interface Publisher {

    ChannelType type();

    /**
     * When present, the framework resolves the channel's connection before every send and holds its
     * deliveries while that account is broken.
     */
    default Optional<String> connectionType() {
        return Optional.empty();
    }

    /**
     * No network. Called on create and on every edit.
     *
     * @throws IllegalArgumentException with a message fit to show a user
     */
    void validateTarget(Map<String, Object> target);

    /**
     * An optional check before a test send, for a clearer message than the platform's own refusal.
     *
     * @param connection null when {@link #connectionType()} is empty
     * @throws PublishException if it cannot send
     */
    default void verify(Channel channel, Connection connection) {
    }

    /**
     * Synchronous: returning means the platform accepted it.
     *
     * @param connection null when {@link #connectionType()} is empty
     * @param reference the delivery id. Delivery is at-least-once, so pass it as an idempotency key where the
     *                  platform accepts one
     * @throws PublishException for a failure to classify; any other exception counts as transient
     */
    PublishReceipt publish(Channel channel, Connection connection, PublishMessage message, String reference);
}
