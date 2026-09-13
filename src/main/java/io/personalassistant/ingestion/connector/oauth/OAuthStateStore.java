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
 * Holds the short-lived context of an in-flight consent: what the user was connecting, where to send
 * them back, and a single-use token proving the callback belongs to a flow this server started.
 *
 * <p>The {@code state} parameter is the CSRF defence the OAuth spec asks for — without it anyone can
 * hand the callback a code of their choosing. It is also the only way the callback knows what it is
 * completing, since the provider echoes nothing else back.
 *
 * <p><strong>Single-node, in memory.</strong> The same accepted trade-off as
 * {@code InMemoryPermitService} (invariant 7). Losing the map across a restart costs the user one
 * click on Connect, which is not worth a Mongo collection and a startup index for data whose whole
 * life is ten minutes.
 */
@ApplicationScoped
public class OAuthStateStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * How long a user has to complete the consent screen before the callback stops being accepted.
     * Public (rather than the usual package-private) because the flow's tests live in {@code app} and
     * set it directly — the same reason {@code IngestionRunner.maxDeferrals} is.
     */
    @ConfigProperty(name = "app.oauth.state-ttl-seconds", defaultValue = "600")
    public long stateTtlSeconds;

    private final Map<String, PendingConnect> pending = new ConcurrentHashMap<>();

    /** Mint a state token for one consent attempt and remember what it is for. */
    public String issue(PendingConnect connect) {
        purgeExpired();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(state, connect);
        return state;
    }

    /**
     * Consume a state token. Single-use by construction — a replayed callback finds nothing, which is
     * the point of the token in the first place.
     *
     * @return the context that was stored, or empty when the token is unknown, replayed or expired
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
     * Read a state token's context without consuming it. Used only to land a provider-reported error
     * (the user pressed Cancel) back on the page they started from — a failed consent must not burn the
     * token, so a retry from the same page still works.
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

    /**
     * One consent in flight.
     *
     * @param providerId   which provider's flow this is
     * @param type         the connector the resulting connection is for
     * @param connectionId existing connection to re-credential, or null to create one
     * @param name         label for the connection when one is being created, or null for a default
     * @param redirectUri  the exact redirect URI sent to the provider; the code exchange must reuse it
     * @param returnTo     the console origin to bounce the browser back to
     * @param startedAt    when the flow began, for expiry
     */
    public record PendingConnect(String providerId, String type, String connectionId, String name,
                                 String redirectUri, String returnTo, Instant startedAt) {

        boolean isExpiredAt(Instant now, Duration ttl) {
            return startedAt.plus(ttl).isBefore(now);
        }
    }
}
