package io.personalassistant.api.dto;

import io.personalassistant.domain.service.OAuthConnectService;

/** @param connectionId existing connection to re-credential, or null to create one */
public record OAuthStartDto(String type, String connectionId, String name) {

    public OAuthConnectService.StartConnect toRequest(String providerId, String origin) {
        return new OAuthConnectService.StartConnect(
                providerId,
                ConnectionDto.requireType(type),
                connectionId,
                name,
                origin);
    }
}
