package io.personalassistant.api.dto;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.service.ConnectionService;
import java.util.Map;

/**
 * @param rateLimit null uses the operator default
 * @param makeDefault make this the type's default; a type's first connection is anyway
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
                requireType(type),
                auth,
                config,
                rateLimit,
                makeDefault != null && makeDefault);
    }

    /** @throws IllegalArgumentException if absent */
    static String requireType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        return type.trim();
    }
}
