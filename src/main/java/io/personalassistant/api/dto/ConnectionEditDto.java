package io.personalassistant.api.dto;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.service.ConnectionService;
import java.util.Map;

/**
 * A field absent or null is left unchanged, so removing a rate limit is an explicit empty rule list
 * ({@code {"rules": []}}). Changing {@code auth} re-verifies the credentials.
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
