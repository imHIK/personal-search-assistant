package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChannelService {

    /**
     * @throws IllegalArgumentException if the name is blank, no publisher handles the type, the publisher
     *                                  refuses the target, or the named connection is missing or of the wrong
     *                                  type
     */
    Channel create(NewChannel request);

    Optional<Channel> get(String id);

    List<Channel> list();

    /**
     * A changed target or account is re-validated.
     *
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     * @throws IllegalArgumentException if the result is invalid
     */
    Channel update(String id, ChannelEdit edit);

    /**
     * Sends synchronously, bypassing the outbox. Does not throw on a failed send: that is how an ERROR
     * channel comes back.
     *
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     */
    Channel test(String id);

    /**
     * Deletes its deliveries too.
     *
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     * @throws IllegalStateException if a digest still sends to it
     */
    void delete(String id);

    /**
     * @param connectionId null for the type's default account
     * @param enabled null means enabled
     */
    record NewChannel(String name, ChannelType type, String connectionId, Map<String, Object> target,
                      Boolean enabled) {
    }

    /**
     * A null field is left unchanged; the type is fixed at creation.
     *
     * @param connectionId a blank string switches back to the default account
     */
    record ChannelEdit(String name, String connectionId, Map<String, Object> target, Boolean enabled) {
    }
}
