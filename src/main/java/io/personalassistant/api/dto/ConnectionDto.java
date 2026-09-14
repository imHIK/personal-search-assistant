package io.personalassistant.api.dto;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.service.ConnectionService;
import java.util.Map;

/**
 * Inbound payload to create a connection ({@code POST /api/connections}). Kept separate from the
 * domain {@link io.personalassistant.domain.model.Connection} so the wire contract can evolve
 * independently.
 *
 * @param name       human-friendly label ("Work Gmail")
 * @param type       connection type (e.g. {@code GMAIL}, {@code GMAIL_SEND})
 * @param auth       opaque credentials (e.g. {@code {"refreshToken": "...", "accessToken": "..."}})
 * @param config     opaque connector-level settings (e.g. an OAuth client), or null
 * @param rateLimit  outbound call ceilings, e.g. {@code {"rules":[{"permits":500,"windowSeconds":60}]}},
 *                   or null to use the operator default
 * @param makeDefault force this to become the type default (first connection of a type is default anyway)
 */
public record ConnectionDto(
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> config,
        RateLimitPolicy rateLimit,
        Boolean makeDefault) {

    public ConnectionService.NewConnection toRequest() {
        return new ConnectionService.NewConnection(
                name,
                requireType(type), // an unknown type is refused by the service → 400
                auth,
                config,
                rateLimit,
                makeDefault != null && makeDefault);
    }

    /** @throws IllegalArgumentException if absent — mapped to a 400 by the resource */
    static String requireType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        return type.trim();
    }
}
