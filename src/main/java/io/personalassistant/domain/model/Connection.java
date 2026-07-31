package io.personalassistant.domain.model;

import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.SourceType;
import java.time.Instant;
import java.util.Map;

/**
 * A reusable, account-level connection to a source: the credentials and connector-level settings a
 * {@link Knowledge} authenticates <em>through</em>. Separating "who am I connecting as" (this record)
 * from "what do I want to index" ({@link Knowledge}) is what lets a user keep several accounts of the
 * same connector — two Gmail logins, a personal and a work Drive — and point different knowledges at
 * whichever they want, without duplicating credentials on every knowledge.
 *
 * <p>Connections are a <strong>generic framework</strong>, not a Google-specific one: a connection is
 * keyed by {@link SourceType} and carries two opaque, connector-defined blobs — {@link #auth}
 * (tokens/keys) and {@link #config} (connector-level settings, e.g. an OAuth client or a base URL).
 * The core never inspects either; each connector reads what it needs. Connectors that need no
 * credentials (e.g. {@code LOCAL_FS}) simply never require a connection (see
 * {@code SourceConnector.requiresConnection()}).
 *
 * <p><strong>Default per type.</strong> At most one connection per {@link SourceType} is
 * {@link #isDefault()}. A knowledge that names no connection resolves to its type's default, so the
 * common single-account case needs no per-knowledge wiring.
 *
 * @param id        stable id, e.g. {@code "conn_..."}
 * @param name      human-friendly label ("Work Gmail")
 * @param type      which connector this connection authenticates
 * @param auth      opaque credentials (access/refresh tokens, API keys); never inspected by the core
 * @param config    opaque connector-level settings (OAuth client, base URL overrides); never inspected
 * @param isDefault whether this is the default connection for {@link #type} (at most one per type)
 * @param status    lifecycle state
 * @param lastError why the last credential verification failed (with {@code ERROR}), else null
 * @param createdAt creation timestamp
 * @param updatedAt last-modified timestamp
 */
public record Connection(
        String id,
        String name,
        SourceType type,
        Map<String, Object> auth,
        Map<String, Object> config,
        boolean isDefault,
        ConnectionStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Connection {
        auth = auth == null ? Map.of() : auth;
        config = config == null ? Map.of() : config;
    }

    /** Copy with a new lifecycle status and error (cleared on a non-ERROR status). */
    public Connection withStatus(ConnectionStatus newStatus, String newLastError) {
        return new Connection(id, name, type, auth, config, isDefault, newStatus,
                newStatus == ConnectionStatus.ERROR ? newLastError : null, createdAt, updatedAt);
    }

    /** Copy with the default flag flipped (used when (re)assigning the per-type default). */
    public Connection asDefault(boolean makeDefault) {
        return new Connection(id, name, type, auth, config, makeDefault, status, lastError,
                createdAt, updatedAt);
    }

    /**
     * Copy with a refreshed {@link #auth} blob and {@code updatedAt} bumped. Written by the token
     * provider when it mints a new access token, so a refreshed credential survives restarts instead
     * of living only in an in-process cache.
     */
    public Connection withAuth(Map<String, Object> newAuth, Instant updatedAt) {
        return new Connection(id, name, type, newAuth, config, isDefault, status, lastError,
                createdAt, updatedAt);
    }

    /** Copy with edited user-facing fields (name / auth / config) and {@code updatedAt} bumped. */
    public Connection withEdits(String newName, Map<String, Object> newAuth,
                                Map<String, Object> newConfig, Instant updatedAt) {
        return new Connection(id, newName, type, newAuth, newConfig, isDefault, status, lastError,
                createdAt, updatedAt);
    }
}
