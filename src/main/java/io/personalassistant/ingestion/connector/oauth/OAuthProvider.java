package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.common.ratelimit.RateLimit;
import java.util.Set;

/**
 * One OAuth application, as a CDI bean. Its id is a URL path segment, so it must stay stable once shipped.
 * The shared machinery lives in OAuthTokenService, and AbstractOAuth2Provider covers RFC 6749.
 */
public interface OAuthProvider {

    /** Stable, lowercase and URL-safe; also names this provider's config keys. */
    String id();

    /** SourceType names, and types other beans register ({@code GMAIL_SEND}). */
    Set<String> supports();

    /** @throws IllegalArgumentException if this provider does not support {@code type} */
    Set<String> scopesFor(String type);

    String authorizeUrl(AuthorizeRequest request);

    /**
     * @param redirectUri must be byte-identical to the one in {@link #authorizeUrl}
     * @throws CredentialsRejectedException if the provider refused the code
     */
    OAuthTokens exchangeCode(String code, String redirectUri, OAuthClient client);

    /**
     * @param limit the account's quota: the token endpoint is called on its behalf
     * @throws CredentialsRejectedException if the refresh token is revoked, expired or foreign
     */
    OAuthTokens refresh(String refreshToken, OAuthClient client, RateLimit limit);
}
