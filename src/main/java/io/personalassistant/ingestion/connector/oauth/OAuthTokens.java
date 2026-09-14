package io.personalassistant.ingestion.connector.oauth;

import java.time.Instant;

/**
 * The credential material an OAuth 2.0 token endpoint hands back, in provider-neutral terms.
 *
 * <p>{@code refreshToken} is nullable on purpose: providers commonly return one only on the first
 * consent, and a refresh response almost never carries one. Callers must therefore treat a null as
 * "keep what you already had" rather than "clear it" — overwriting a working refresh token with null
 * is how an account silently stops syncing.
 *
 * @param accessToken       short-lived bearer token
 * @param refreshToken      long-lived token, or null when the provider returned none this time
 * @param expiresAtEpochSec absolute expiry of {@code accessToken}, in Unix seconds
 */
public record OAuthTokens(String accessToken, String refreshToken, long expiresAtEpochSec) {

    /** Default lifetime assumed when a provider omits {@code expires_in}; one hour is the common case. */
    public static final long DEFAULT_TTL_SECONDS = 3600;

    public OAuthTokens {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        refreshToken = refreshToken == null || refreshToken.isBlank() ? null : refreshToken;
    }

    /** Build from a relative {@code expires_in}, which is what token endpoints actually return. */
    public static OAuthTokens expiringIn(String accessToken, String refreshToken, long ttlSeconds) {
        return new OAuthTokens(accessToken, refreshToken,
                Instant.now().getEpochSecond() + (ttlSeconds <= 0 ? DEFAULT_TTL_SECONDS : ttlSeconds));
    }
}
