package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Optional;

/** Persistence port for the {@code connections} collection. */
public interface ConnectionRepository {

    /** Insert or replace by {@code id}. */
    Connection save(Connection connection);

    Optional<Connection> findById(String id);

    List<Connection> findAll();

    List<Connection> findByType(SourceType type);

    /** The default connection for a type, if one is marked (at most one per type). */
    Optional<Connection> findDefault(SourceType type);

    /**
     * Clear the default flag on whatever connection currently holds it for {@code type}, so a new
     * default can be assigned atomically-enough for a single-writer admin flow (create/set-default).
     */
    void clearDefault(SourceType type);

    void delete(String id);
}
