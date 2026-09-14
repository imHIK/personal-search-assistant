package io.personalassistant.domain.service;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.model.Connection;
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
     * @throws IllegalArgumentException nothing registers the connection type, or credential
     *                                  verification fails
     */
    Connection create(NewConnection request);

    Optional<Connection> get(String id);

    List<Connection> list();

    List<Connection> listByType(String type);

    /**
     * Edit a connection's user-facing fields (present fields only). Changing {@code auth} re-verifies
     * the credentials.
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection update(String id, ConnectionEdit edit);

    /**
     * Re-check a stored connection's credentials against its connector and record the outcome on the
     * connection ({@code ACTIVE} / {@code ERROR} plus {@code lastError}).
     *
     * <p>Credentials are only verified at create and on an auth edit, so a token that expires
     * afterwards leaves a connection reading {@code ACTIVE} while every sync fails. This is what makes
     * that visible without waiting for a user to notice their data has gone stale.
     *
     * <p>Does <strong>not</strong> throw on bad credentials: a failed check is a result, not an error —
     * the caller wants to display it, and the scheduled sweep must carry on to the next connection.
     *
     * @return the connection as it now stands, with its refreshed status
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection test(String id);

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
     * @throws IllegalStateException            if knowledges or channels still reference it
     */
    void delete(String id);

    /**
     * Inputs to register a connection.
     *
     * @param name        human-friendly label
     * @param type        connection type, e.g. {@code GMAIL} or {@code GMAIL_SEND}
     * @param auth        opaque credentials
     * @param config      opaque connector-level settings, or null
     * @param rateLimit   outbound call ceilings for this account, or null for the operator default
     * @param makeDefault force this to become the type default (first-of-type is default regardless)
     */
    record NewConnection(String name, String type, Map<String, Object> auth,
                         Map<String, Object> config, RateLimitPolicy rateLimit,
                         boolean makeDefault) {}

    /**
     * A partial edit; a null field is left unchanged.
     *
     * <p>{@code rateLimit} therefore needs an explicit empty rule list to remove a limit — null cannot
     * mean both "leave it alone" and "clear it", and leaving it alone is the far more common intent.
     */
    record ConnectionEdit(String name, Map<String, Object> auth, Map<String, Object> config,
                          RateLimitPolicy rateLimit) {}
}
