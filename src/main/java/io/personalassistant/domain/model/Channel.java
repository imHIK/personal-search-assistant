package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import java.time.Instant;
import java.util.Map;

/**
 * Somewhere a message can be published: "my inbox", "the family WhatsApp group", "#alerts".
 *
 * <p>{@link #target} is an opaque, publisher-defined blob in the same spirit as
 * {@link Connection#config()} — the core never inspects it; the publisher for {@link #type} validates
 * and reads it. For {@code EMAIL} it is {@code {to: [...], cc: [...], subjectPrefix: "..."}}.
 *
 * <p>{@link #connectionId} is a first-class field rather than a key in {@link #target} for the reason
 * {@code Connection.rateLimit} is: the core reads it. The delivery worker resolves the account before
 * sending, and deleting a connection is refused while a channel still sends through it.
 *
 * @param id           stable id, {@code chn_...}
 * @param name         human label ("My inbox")
 * @param type         which publisher delivers it
 * @param connectionId the account it sends through, for a publisher that sends through one; null means
 *                     that connection type's default account
 * @param target       publisher-defined destination settings; never inspected by the core
 * @param enabled      master switch. A disabled channel's deliveries wait rather than fail — pausing is
 *                     not a reason to lose messages
 * @param status       whether the last check or send succeeded
 * @param lastError    why, with {@code ERROR}; otherwise null
 * @param createdAt    creation timestamp
 * @param updatedAt    last-modified timestamp
 */
public record Channel(
        String id,
        String name,
        ChannelType type,
        String connectionId,
        Map<String, Object> target,
        boolean enabled,
        ChannelStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Channel {
        connectionId = connectionId == null || connectionId.isBlank() ? null : connectionId;
        target = target == null ? Map.of() : target;
        status = status == null ? ChannelStatus.ACTIVE : status;
    }

    /** Whether the delivery worker should send to it now, as far as the channel itself goes. */
    public boolean usable() {
        return enabled && status == ChannelStatus.ACTIVE;
    }

    /** Copy with a new status; the error is kept only for {@code ERROR}. */
    public Channel withStatus(ChannelStatus newStatus, String newLastError, Instant at) {
        return new Channel(id, name, type, connectionId, target, enabled, newStatus,
                newStatus == ChannelStatus.ERROR ? newLastError : null, createdAt, at);
    }

    /** Copy with edited user-facing fields and {@code updatedAt} bumped. */
    public Channel withEdits(String newName, String newConnectionId, Map<String, Object> newTarget,
                             boolean nowEnabled, Instant at) {
        return new Channel(id, newName, type, newConnectionId, newTarget, nowEnabled, status, lastError,
                createdAt, at);
    }
}
