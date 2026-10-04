package io.personalassistant.api.dto;

import io.personalassistant.domain.service.ChannelService;
import java.util.Map;

/**
 * A null or absent field is left unchanged.
 *
 * @param connectionId {@code ""} switches back to the default account
 */
public record ChannelEditDto(String name, String connectionId, Map<String, Object> target, Boolean enabled) {

    public ChannelService.ChannelEdit toEdit() {
        return new ChannelService.ChannelEdit(name, connectionId, target, enabled);
    }
}
