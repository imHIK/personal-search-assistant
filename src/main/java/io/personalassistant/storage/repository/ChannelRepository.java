package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for the {@code channels} collection.
 *
 * <p>Writes after creation are field-level, split by owner: a person owns name, account, target and the
 * enabled switch; the delivery worker and the test action own status. A whole-document save from either
 * side would clobber the other — an edit made while a send failed would silently put a broken channel
 * back to {@code ACTIVE}.
 */
public interface ChannelRepository {

    Channel insert(Channel channel);

    Optional<Channel> findById(String id);

    /** Newest first. */
    List<Channel> findAll();

    /** Channels the delivery worker may send to now: enabled and {@code ACTIVE}. */
    List<Channel> findUsable();

    /** Channels that name this connection explicitly — the delete guard's question. */
    List<Channel> findByConnectionId(String connectionId);

    /** @return false if no such channel */
    boolean updateEdits(String id, String name, String connectionId, Map<String, Object> target,
                        boolean enabled, Instant at);

    /** @return false if no such channel */
    boolean updateStatus(String id, ChannelStatus status, String lastError, Instant at);

    void delete(String id);
}
