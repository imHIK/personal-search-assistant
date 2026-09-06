package io.personalassistant.api.dto;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.service.ConnectionService;
import java.util.Map;

/**
 * Inbound payload for a partial edit ({@code PATCH /api/connections/{id}}). A field that is absent
 * (or JSON {@code null}) is left unchanged; {@code type} is immutable and not editable here. Changing
 * {@code auth} triggers re-verification of the credentials.
 *
 * <p>Because absent means unchanged, removing a rate limit is expressed as an explicit empty rule list
 * ({@code {"rateLimit": {"rules": []}}}) rather than by sending null.
 */
public record ConnectionEditDto(
        String name,
        Map<String, Object> auth,
        Map<String, Object> config,
        RateLimitPolicy rateLimit) {

    public ConnectionService.ConnectionEdit toEdit() {
        return new ConnectionService.ConnectionEdit(name, auth, config, rateLimit);
    }
}
