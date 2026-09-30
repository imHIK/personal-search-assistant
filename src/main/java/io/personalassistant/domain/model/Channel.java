package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import java.time.Instant;
import java.util.Map;

/**
 * @param connectionId null means the default account of the publisher's connection type
 * @param target publisher-defined; never inspected by the core
 * @param enabled a disabled channel's deliveries wait rather than fail
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

    public boolean usable() {
        return enabled && status == ChannelStatus.ACTIVE;
    }

    /** The error is kept only for ERROR. */
    public Channel withStatus(ChannelStatus newStatus, String newLastError, Instant at) {
        return new Channel(id, name, type, connectionId, target, enabled, newStatus,
                newStatus == ChannelStatus.ERROR ? newLastError : null, createdAt, at);
    }

    public Channel withEdits(String newName, String newConnectionId, Map<String, Object> newTarget,
                             boolean nowEnabled, Instant at) {
        return new Channel(id, newName, type, newConnectionId, newTarget, nowEnabled, status, lastError,
                createdAt, at);
    }
}
