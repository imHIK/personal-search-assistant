package io.personalassistant.ingestion.connector.oauth;

import io.personalassistant.common.ratelimit.RateLimit;
import java.util.Set;

/**
 * SPI for one OAuth 2.0 application the user can connect an account through (Google today; Slack,
 * Notion, Dropbox tomorrow).
 *
 * <p>Discovered by CDI exactly like {@code SourceConnector}: adding a provider is adding an
 * {@code @ApplicationScoped} bean, and nothing — no registry, no resource, no enum — is edited to make
 * its endpoints exist. {@link #id()} becomes a path segment under
 * {@code /api/connections/oauth/{provider}/…}, so it is part of the public contract and must stay
 * stable once shipped.
 *
 * <p>Everything that is genuinely the same for every provider — state handling, client resolution,
 * token caching, persistence, credential-rejection reaction — lives in {@link OAuthTokenService} and
 * its neighbours, not here. An implementation only supplies what its vendor actually does
 * differently, and {@link AbstractOAuth2Provider} already covers the RFC 6749 parts most vendors share.
 */
public interface OAuthProvider {

    /** Stable, lowercase, URL-safe identifier ({@code "google"}). Also names this provider's config keys. */
    String id();

    /**
     * The connection types this provider can authenticate: {@code SourceType} names for connectors, and
     * any type something else registers (the email publisher's {@code GMAIL_SEND}).
     */
    Set<String> supports();

    /**
     * The permissions to request for one connector type. Kept here so scope strings live beside the
     * provider that understands them, rather than in a {@code switch} in the API or use-case layer.
     *
     * @throws IllegalArgumentException if this provider does not support {@code type}
     */
    Set<String> scopesFor(String type);

    /** The consent URL to send the user's browser to. */
    String authorizeUrl(AuthorizeRequest request);

    /**
     * Trade a one-time authorization code for tokens.
     *
     * @param redirectUri must be byte-identical to the one used in {@link #authorizeUrl}
     * @throws CredentialsRejectedException if the provider refused the code
     */
    OAuthTokens exchangeCode(String code, String redirectUri, OAuthClient client);

    /**
     * Mint a fresh access token from a refresh token.
     *
     * @param limit the account's quota — a token endpoint has a quota like any other endpoint, and the
     *              refresh is a call made on this account's behalf
     * @throws CredentialsRejectedException if the refresh token is revoked, expired or foreign
     */
    OAuthTokens refresh(String refreshToken, OAuthClient client, RateLimit limit);
}
