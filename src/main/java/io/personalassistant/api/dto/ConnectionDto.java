package io.personalassistant.api.dto;

import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.ConnectionService;
import java.util.Map;

/**
 * Inbound payload to create a connection ({@code POST /api/connections}). Kept separate from the
 * domain {@link io.personalassistant.domain.model.Connection} so the wire contract can evolve
 * independently.
 *
 * @param name       human-friendly label ("Work Gmail")
 * @param type       connector type name (e.g. {@code GMAIL})
 * @param auth       opaque credentials (e.g. {@code {"refreshToken": "...", "accessToken": "..."}})
 * @param config     opaque connector-level settings (e.g. an OAuth client), or null
 * @param makeDefault force this to become the type default (first connection of a type is default anyway)
 */
public record ConnectionDto(
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> config,
        Boolean makeDefault) {

    public ConnectionService.NewConnection toRequest() {
        return new ConnectionService.NewConnection(
                name,
                SourceType.valueOf(type), // bad enum → IllegalArgumentException → 400
                auth,
                config,
                makeDefault != null && makeDefault);
    }
}
