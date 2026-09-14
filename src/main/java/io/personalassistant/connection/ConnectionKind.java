package io.personalassistant.connection;

import io.personalassistant.domain.model.Connection;

/**
 * One kind of credential a {@link Connection} can hold, named by its connection type, and how to prove
 * a stored one still works.
 *
 * <p>This is what separates connections from the knowledge flow. A connection used to be typed by
 * {@code SourceType} and verified only by that type's {@code SourceConnector}, so nothing but a source
 * could own an account. Now anything can: every connector that needs credentials is still a kind —
 * registered automatically from the connector, see {@link CdiConnectionKindRegistry} — and a publisher
 * that sends through an account registers its own ({@code GMAIL_SEND}).
 *
 * <p>Discovered by CDI; adding a kind is adding an {@code @ApplicationScoped} bean.
 */
public interface ConnectionKind {

    /**
     * The connection type this kind handles, stored as {@code Connection.type}. Part of the persisted
     * contract once shipped: renaming it orphans every stored connection of the old name.
     */
    String id();

    /**
     * Prove the credentials work. Called when a connection is created, when its credentials are edited,
     * and by the health sweep.
     *
     * @throws RuntimeException with a message fit to show a user; the service turns it into a 400 on a
     *                          save and into {@code ERROR} + {@code lastError} on a check
     */
    void verify(Connection connection);
}
