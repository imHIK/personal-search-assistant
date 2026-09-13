package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Use-case port for managing publishing {@link Channel}s. */
public interface ChannelService {

    /**
     * @throws IllegalArgumentException if the name is blank, no publisher handles the type, the publisher
     *                                  refuses the target, or the named connection is missing or of the
     *                                  wrong type
     */
    Channel create(NewChannel request);

    Optional<Channel> get(String id);

    List<Channel> list();

    /**
     * Edit the present fields; a changed target or account is re-validated.
     *
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     * @throws IllegalArgumentException         if the result is invalid
     */
    Channel update(String id, ChannelEdit edit);

    /**
     * Send a fixed sample message synchronously, bypassing the outbox, and record the outcome as the
     * channel's status. Does <strong>not</strong> throw on a failed send: the caller wants to display it,
     * and it is how a channel parked in {@code ERROR} is brought back.
     *
     * @return the channel as it now stands
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     */
    Channel test(String id);

    /**
     * Delete a channel and every delivery queued or recorded for it.
     *
     * @throws java.util.NoSuchElementException if no channel with {@code id} exists
     * @throws IllegalStateException            if a digest still sends to it
     */
    void delete(String id);

    /**
     * @param name         human label
     * @param type         which publisher delivers it
     * @param connectionId the account to send through, or null for the type's default
     * @param target       publisher-defined destination settings
     * @param enabled      null means enabled
     */
    record NewChannel(String name, ChannelType type, String connectionId, Map<String, Object> target,
                      Boolean enabled) {
    }

    /**
     * A partial edit; a null field is left unchanged. The type is fixed at creation.
     *
     * @param connectionId null leaves the account alone; a blank string switches back to the default
     *                     account — the only way to express "stop pinning this one", since null already
     *                     means unchanged
     */
    record ChannelEdit(String name, String connectionId, Map<String, Object> target, Boolean enabled) {
    }
}
