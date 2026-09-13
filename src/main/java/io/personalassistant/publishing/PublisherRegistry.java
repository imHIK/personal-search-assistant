package io.personalassistant.publishing;

import io.personalassistant.domain.model.enums.ChannelType;
import java.util.Set;

/** Resolves the {@link Publisher} for a channel type. */
public interface PublisherRegistry {

    /** @throws IllegalArgumentException if no publisher is registered for {@code type} */
    Publisher get(ChannelType type);

    boolean supports(ChannelType type);

    /** Every type that currently has a publisher. */
    Set<ChannelType> supported();
}
