package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.storage.repository.ChannelRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link ChannelRepository} for tests. */
public class InMemoryChannelRepository implements ChannelRepository {

    public final Map<String, Channel> store = new LinkedHashMap<>();

    @Override
    public Channel insert(Channel channel) {
        store.put(channel.id(), channel);
        return channel;
    }

    @Override
    public Optional<Channel> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Channel> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<Channel> findUsable() {
        return store.values().stream().filter(Channel::usable).toList();
    }

    @Override
    public List<Channel> findByConnectionId(String connectionId) {
        return store.values().stream().filter(c -> connectionId.equals(c.connectionId())).toList();
    }

    @Override
    public boolean updateEdits(String id, String name, String connectionId, Map<String, Object> target,
                               boolean enabled, Instant at) {
        return store.computeIfPresent(id, (k, c) -> c.withEdits(name, connectionId, target, enabled, at)) != null;
    }

    @Override
    public boolean updateStatus(String id, ChannelStatus status, String lastError, Instant at) {
        return store.computeIfPresent(id, (k, c) -> c.withStatus(status, lastError, at)) != null;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
