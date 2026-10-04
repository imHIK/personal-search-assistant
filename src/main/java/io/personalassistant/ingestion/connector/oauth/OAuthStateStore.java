package io.personalassistant.ingestion.connector.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The state token is the CSRF defence: without it anyone could hand the callback a code of their choosing,
 * and it is how the callback knows what it completes. In memory and single-node: a restart costs one click on
 * Connect.
 */
@ApplicationScoped
public class OAuthStateStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Public rather than package-private: the flow's tests live in {@code app}. */
    @ConfigProperty(name = "app.oauth.state-ttl-seconds", defaultValue = "600")
    public long stateTtlSeconds;

    private final Map<String, PendingConnect> pending = new ConcurrentHashMap<>();

    public String issue(PendingConnect connect) {
        purgeExpired();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(state, connect);
        return state;
    }

    /**
     * Single-use: a replayed callback finds nothing.
     *
     * @return empty when the token is unknown, replayed or expired
     */
    public Optional<PendingConnect> consume(String state) {
        purgeExpired();
        PendingConnect connect = state == null ? null : pending.remove(state);
        if (connect == null || connect.isExpiredAt(Instant.now(), ttl())) {
            return Optional.empty();
        }
        return Optional.of(connect);
    }

    /**
     * Does not consume the token: a cancelled consent must not burn it, so a retry from the same page works.
     */
    public Optional<PendingConnect> peek(String state) {
        PendingConnect connect = state == null ? null : pending.get(state);
        if (connect == null || connect.isExpiredAt(Instant.now(), ttl())) {
            return Optional.empty();
        }
        return Optional.of(connect);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Duration ttl = ttl();
        pending.values().removeIf(connect -> connect.isExpiredAt(now, ttl));
    }

    private Duration ttl() {
        return Duration.ofSeconds(stateTtlSeconds);
    }

    /** @param redirectUri the exact one sent to the provider; the code exchange must reuse it */
    public record PendingConnect(String providerId, String type, String connectionId, String name,
                                 String redirectUri, String returnTo, Instant startedAt) {

        boolean isExpiredAt(Instant now, Duration ttl) {
            return startedAt.plus(ttl).isBefore(now);
        }
    }
}
