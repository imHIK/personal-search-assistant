package io.personalassistant.ingestion.connector.google;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.ingestion.connector.oauth.OAuthTokenService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
