package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.storage.repository.ConnectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Simple in-memory {@link ConnectionRepository} for unit tests (no Mongo). */
public class InMemoryConnectionRepository implements ConnectionRepository {

    public final Map<String, Connection> store = new LinkedHashMap<>();

    @Override
    public Connection save(Connection connection) {
        store.put(connection.id(), connection);
        return connection;
    }

    @Override
    public Optional<Connection> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Connection> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Connection> findByType(SourceType type) {
        return store.values().stream().filter(c -> c.type() == type).toList();
    }

    @Override
    public Optional<Connection> findDefault(SourceType type) {
        return store.values().stream().filter(c -> c.type() == type && c.isDefault()).findFirst();
    }

    @Override
    public void clearDefault(SourceType type) {
        store.values().stream()
                .filter(c -> c.type() == type && c.isDefault())
                .toList()
                .forEach(c -> store.put(c.id(), c.asDefault(false)));
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
