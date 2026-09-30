package io.personalassistant.domain.service;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.domain.model.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConnectionService {

    /**
     * @throws IllegalArgumentException if nothing registers the connection type, or the credentials fail
     *                                  verification
     */
    Connection create(NewConnection request);

    Optional<Connection> get(String id);

    List<Connection> list();

    List<Connection> listByType(String type);

    /**
     * Changing {@code auth} re-verifies the credentials.
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection update(String id, ConnectionEdit edit);

    /**
     * Does not throw on bad credentials: a failed check is a result, and the scheduled sweep must carry on.
     *
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     */
    Connection test(String id);

    /** @throws java.util.NoSuchElementException if no connection with {@code id} exists */
    Connection setDefault(String id);

    /**
     * @throws java.util.NoSuchElementException if no connection with {@code id} exists
     * @throws IllegalStateException if knowledges or channels still reference it
     */
    void delete(String id);

    /**
     * @param rateLimit null for the operator default
     * @param makeDefault a type's first connection is the default regardless
     */
    record NewConnection(String name, String type, Map<String, Object> auth,
                         Map<String, Object> config, RateLimitPolicy rateLimit,
                         boolean makeDefault) {}

    /** A null field is left unchanged, so removing a rate limit takes an explicit empty rule list. */
    record ConnectionEdit(String name, Map<String, Object> auth, Map<String, Object> config,
                          RateLimitPolicy rateLimit) {}
}
