package io.personalassistant.publishing;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelType;
import java.util.Map;
import java.util.Optional;

/**
 * THE extension point for outbound messages: one implementation per platform (email, WhatsApp,
 * Slack…). Discovered by CDI exactly like {@code SourceConnector} — adding a platform is adding an
 * {@code @ApplicationScoped} bean, and nothing else in the core changes.
 *
 * <p>A publisher owns everything platform-shaped: what its channel's {@code target} blob must contain,
 * how a {@link PublishMessage} is rendered (HTML, a length-capped text, blocks), and which of its
 * transport's failures are worth retrying. The outbox, retries, leases, channel status and resolving
 * which account to send through are the framework's, and a publisher never touches them.
 */
public interface Publisher {

    /** Which channel type this publisher delivers; used by the registry to select it. */
    ChannelType type();

    /**
     * The connection type this publisher sends through, or empty for one that needs no account. When
     * present, the framework resolves the channel's connection (its own, or the type's default) before
     * every send, and holds deliveries while that account is broken or disconnected.
     */
    default Optional<String> connectionType() {
        return Optional.empty();
    }

    /**
     * Check a channel's {@code target} blob without touching the network. Called on create and on every
     * edit, so a channel that could never deliver is refused at the door rather than discovered on the
     * first send.
     *
     * @throws IllegalArgumentException with a message fit to show a user
     */
    void validateTarget(Map<String, Object> target);

    /**
     * Prove the channel can send before a test send — typically that its account granted what sending
     * needs, which gives a clearer message than the platform's own refusal would. Optional.
     *
     * @param connection the resolved account, or null when {@link #connectionType()} is empty
     * @throws PublishException if it cannot
     */
    default void verify(Channel channel, Connection connection) {
    }

    /**
     * Send one message. Synchronous: returning means the platform accepted it.
     *
     * @param connection the resolved account, or null when {@link #connectionType()} is empty
     * @param reference  a stable id for this send (the delivery id). Delivery is at-least-once, so a
     *                   publisher whose platform accepts an idempotency key (Slack's {@code client_msg_id})
     *                   should pass this as that key. Gmail accepts none, and replaces any Message-ID on send
     * @throws PublishException for a failure the framework should classify; any other runtime exception
     *                          is treated as transient
     */
    PublishReceipt publish(Channel channel, Connection connection, PublishMessage message, String reference);
}
