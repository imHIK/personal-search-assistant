package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.service.ChannelService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Wire shape for a publishing channel, inbound (create) and outbound (read).
 *
 * @param type         {@code EMAIL}, …; a string rather than the enum so an unknown value is a 400 that
 *                     names the problem instead of a generic deserialisation failure
 * @param connectionId the account it sends through, or null for the default account of the type its
 *                     publisher uses ({@code GMAIL_SEND} for email)
 * @param target       publisher-defined destination; for {@code EMAIL}
 *                     {@code {to: [...], cc: [...], subjectPrefix: "..."}}
 * @param enabled      null on create means enabled
 * @param status       {@code ACTIVE} / {@code ERROR}; read-only — set by sends and by the test action
 * @param lastError    why the last send or test failed, with {@code ERROR}; read-only
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
