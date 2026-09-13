package io.personalassistant.ingestion.connector.google;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.ingestion.connector.oauth.OAuthTokenService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Default {@link GoogleAccessTokens} implementation — a thin Google-shaped facade over the
 * provider-neutral {@link OAuthTokenService}.
 *
 * <p>It stays as its own type because {@link GoogleAuth} pairs the bearer with <em>Google's</em> rate
 * limit bucket and is threaded through {@code GoogleHttp}, both API clients and both connectors. The
 * credential handling behind it — refresh, caching, write-back, and the reaction to a revoked grant —
 * is not Google-specific and no longer lives here: see {@link OAuthTokenService} and
 * {@link GoogleOAuthProvider}. A second OAuth connector reuses that machinery and writes only its own
 * equivalent of this class, or none at all.
 */
@ApplicationScoped
public class DefaultGoogleAccessTokens implements GoogleAccessTokens {

    private final OAuthTokenService tokens;
    private final RateLimitPolicies policies;

    @Inject
    public DefaultGoogleAccessTokens(OAuthTokenService tokens, RateLimitPolicies policies) {
        this.tokens = tokens;
        this.policies = policies;
    }

    @Override
    public GoogleAuth authFor(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("No connection to authenticate with");
        }
        RateLimit limit = policies.forConnection(connection.id(), connection.type(),
                connection.rateLimit(), RateLimitMode.WAIT);
        return new GoogleAuth(tokens.bearer(connection, limit), limit);
    }
}
