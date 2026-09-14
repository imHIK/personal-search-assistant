package io.personalassistant.api.dto;

import io.personalassistant.domain.service.ChannelService;
import java.util.Map;

/**
 * A partial channel edit: a null or absent field is left unchanged. Only {@code connectionId} has an
 * "off" state to express — "stop pinning an account, use the default" — and it is spelled as an empty
 * string, so the tree-reading PATCH that digests need is unnecessary here.
 *
 * @param name         new label
 * @param connectionId the account to send through; {@code ""} switches back to the default account
 * @param target       replaces the whole target, re-validated by the channel's publisher
 * @param enabled      pause or resume
 */
public record ChannelEditDto(String name, String connectionId, Map<String, Object> target, Boolean enabled) {

    public ChannelService.ChannelEdit toEdit() {
        return new ChannelService.ChannelEdit(name, connectionId, target, enabled);
    }
}
