package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes after creation are field-level, split by owner: a person owns name, account, target and enabled; the
 * delivery worker and the test action own status. A whole-document save from either side would clobber the
 * other.
 */
public interface ChannelRepository {

    Channel insert(Channel channel);

    Optional<Channel> findById(String id);

    /** Newest first. */
    List<Channel> findAll();

    /** Enabled and ACTIVE. */
    List<Channel> findUsable();

    List<Channel> findByConnectionId(String connectionId);

    /** @return false if no such channel */
    boolean updateEdits(String id, String name, String connectionId, Map<String, Object> target,
                        boolean enabled, Instant at);

    /** @return false if no such channel */
    boolean updateStatus(String id, ChannelStatus status, String lastError, Instant at);

    void delete(String id);
}
