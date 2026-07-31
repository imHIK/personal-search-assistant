package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Use-case port for managing reusable, per-account {@link Connection}s: create (verify credentials →
 * persist → assign the per-type default), list, edit, re-point the default, and delete (guarded by
 * referential integrity). Knowledges bind to a connection by id, or fall back to the type default.
 */
public interface ConnectionService {

    /**
     * Verify the credentials against the connector, persist the connection, and make it the default
     * for its type when requested (or when it is the first connection of that type).
     *
     * @throws IllegalArgumentException if the type has no connector, the connector needs no
     *                                  connection, or credential verification fails
     */
    Connection create(NewConnection request);

    Optional<Connection> get(String id);

    List<Connection> list();

    List<Connection> listByType(SourceType type);

    /**
     * Edit a connection's user-facing fields (present fields only). Changing {@code auth} re-verifies
     * the credentials.
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection update(String id, ConnectionEdit edit);

    /**
     * Make this connection the default for its type (demoting the previous default).
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection setDefault(String id);

    /**
     * Delete a connection. Blocked while any knowledge still binds to it (referential integrity);
     * if it was the type default, the oldest remaining connection of that type is promoted.
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     * @throws IllegalStateException            if one or more knowledges still reference it
     */
    void delete(String id);

    /**
     * Inputs to register a connection.
     *
     * @param name        human-friendly label
     * @param type        connector type
     * @param auth        opaque credentials
     * @param config      opaque connector-level settings, or null
     * @param makeDefault force this to become the type default (first-of-type is default regardless)
     */
    record NewConnection(String name, SourceType type, Map<String, Object> auth,
                         Map<String, Object> config, boolean makeDefault) {}

    /** A partial edit; a null field is left unchanged. */
    record ConnectionEdit(String name, Map<String, Object> auth, Map<String, Object> config) {}
}
