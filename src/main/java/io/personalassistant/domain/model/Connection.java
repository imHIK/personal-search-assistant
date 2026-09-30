package io.personalassistant.domain.model;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import java.time.Instant;
import java.util.Map;

/**
 * @param type a SourceType name for a connector's account, or a type another bean registers
 *             ({@code GMAIL_SEND})
 * @param auth opaque; never inspected by the core
 * @param config opaque; never inspected by the core
 * @param rateLimit the one field the core reads; null or empty means the operator default applies
 * @param isDefault at most one per type; a knowledge naming no connection uses it
 */
public record Connection(
        String id,
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> config,
        RateLimitPolicy rateLimit,
        boolean isDefault,
        ConnectionStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Connection {
        auth = auth == null ? Map.of() : auth;
        config = config == null ? Map.of() : config;
    }

    /** The error is cleared on a non-ERROR status. */
    public Connection withStatus(ConnectionStatus newStatus, String newLastError) {
        return new Connection(id, name, type, auth, config, rateLimit, isDefault, newStatus,
                newStatus == ConnectionStatus.ERROR ? newLastError : null, createdAt, updatedAt);
    }

    public Connection asDefault(boolean makeDefault) {
        return new Connection(id, name, type, auth, config, rateLimit, makeDefault, status, lastError,
                createdAt, updatedAt);
    }

    /** Written when a new access token is minted, so a refreshed credential survives restarts. */
    public Connection withAuth(Map<String, Object> newAuth, Instant updatedAt) {
        return new Connection(id, name, type, newAuth, config, rateLimit, isDefault, status, lastError,
                createdAt, updatedAt);
    }

    /**
     * An empty {@code newRateLimit} is stored as such: it is the only way to remove a limit, since null means
     * unchanged.
     */
    public Connection withEdits(String newName, Map<String, Object> newAuth,
                                Map<String, Object> newConfig, RateLimitPolicy newRateLimit,
                                Instant updatedAt) {
        return new Connection(id, newName, type, newAuth, newConfig, newRateLimit, isDefault, status,
                lastError, createdAt, updatedAt);
    }
}
