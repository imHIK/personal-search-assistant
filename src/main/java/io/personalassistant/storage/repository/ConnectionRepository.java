package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Connection;
import java.util.List;
import java.util.Optional;

public interface ConnectionRepository {

    /** Insert or replace by id. */
    Connection save(Connection connection);

    Optional<Connection> findById(String id);

    List<Connection> findAll();

    List<Connection> findByType(String type);

    /** At most one per type. */
    Optional<Connection> findDefault(String type);

    /**
     * Clears the type's default flag so a new default can be assigned; atomic enough for the single-writer
     * admin flow.
     */
    void clearDefault(String type);

    void delete(String id);
}
