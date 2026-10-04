package io.personalassistant.connection;

import io.personalassistant.domain.model.Connection;

/**
 * A credential type a Connection can hold, and how to verify it. Connectors that need credentials register
 * automatically; anything else, such as a publisher's send account, adds a bean.
 */
public interface ConnectionKind {

    /** Stored as {@code Connection.type}: renaming it orphans every stored connection of the old name. */
    String id();

    /** @throws RuntimeException with a message fit to show a user */
    void verify(Connection connection);
}
