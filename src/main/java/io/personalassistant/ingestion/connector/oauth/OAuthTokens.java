package io.personalassistant.ingestion.connector.oauth;

import java.time.Instant;

/**
 * @param refreshToken null when the provider returned none this time, which means keep the stored one, never
 *                     clear it
 */
public record OAuthTokens(String accessToken, String refreshToken, long expiresAtEpochSec) {

    /** Assumed when a provider omits {@code expires_in}. */
    public static final long DEFAULT_TTL_SECONDS = 3600;

    public OAuthTokens {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        refreshToken = refreshToken == null || refreshToken.isBlank() ? null : refreshToken;
    }

    public static OAuthTokens expiringIn(String accessToken, String refreshToken, long ttlSeconds) {
        return new OAuthTokens(accessToken, refreshToken,
                Instant.now().getEpochSecond() + (ttlSeconds <= 0 ? DEFAULT_TTL_SECONDS : ttlSeconds));
    }
}
