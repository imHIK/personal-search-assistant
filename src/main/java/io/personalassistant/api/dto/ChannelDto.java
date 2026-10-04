package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.service.ChannelService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * @param type a string, not the enum, so an unknown value is a 400 that names it
 * @param connectionId null sends through the default account of the publisher's type
 * @param enabled null on create means enabled
 */
public record ChannelDto(
        String id,
        String name,
        String type,
        String connectionId,
        Map<String, Object> target,
        Boolean enabled,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static ChannelDto from(Channel c) {
        return new ChannelDto(c.id(), c.name(), c.type() == null ? null : c.type().name(), c.connectionId(),
                c.target(), c.enabled(), c.status() == null ? null : c.status().name(), c.lastError(),
                c.createdAt(), c.updatedAt());
    }

    /** @throws IllegalArgumentException if the type is missing or unknown */
    public ChannelService.NewChannel toRequest() {
        return new ChannelService.NewChannel(name, parseType(type), connectionId, target, enabled);
    }

    static ChannelType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        try {
            return ChannelType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown channel type: " + type);
        }
    }
}
