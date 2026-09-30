package io.personalassistant.publishing;

import io.personalassistant.domain.model.enums.ChannelType;
import java.util.Set;

public interface PublisherRegistry {

    /** @throws IllegalArgumentException if no publisher is registered for {@code type} */
    Publisher get(ChannelType type);

    boolean supports(ChannelType type);

    Set<ChannelType> supported();
}
