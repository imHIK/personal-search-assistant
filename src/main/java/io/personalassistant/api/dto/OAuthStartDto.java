package io.personalassistant.api.dto;

import io.personalassistant.domain.service.OAuthConnectService;

/**
 * Inbound payload to begin an OAuth consent flow
 * ({@code POST /api/connections/oauth/{provider}/start}).
 *
 * @param type         connection type the account is being connected for (e.g. {@code GMAIL})
 * @param connectionId existing connection to re-credential, or null to create a new one
 * @param name         label for a newly created connection, or null for a generated one
 */
public record OAuthStartDto(String type, String connectionId, String name) {

    public OAuthConnectService.StartConnect toRequest(String providerId, String origin) {
        return new OAuthConnectService.StartConnect(
                providerId,
                ConnectionDto.requireType(type), // absent → IllegalArgumentException → 400
                connectionId,
                name,
                origin);
    }
}
