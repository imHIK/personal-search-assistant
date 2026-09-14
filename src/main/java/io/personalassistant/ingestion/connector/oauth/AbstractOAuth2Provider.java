package io.personalassistant.ingestion.connector.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The RFC 6749 half of an {@link OAuthProvider}: an authorization-code URL and a form-encoded token
 * endpoint, which is what nearly every vendor implements. A concrete provider supplies its two
 * endpoints, its scope mapping and any vendor-specific authorize parameters, and inherits the rest —
 * so the second provider costs about forty lines, not a second copy of this file.
 *
 * <p>The one genuinely vendor-specific judgement left abstract-ish is
 * {@link #isCredentialsRejected}: telling "this grant is permanently dead" apart from "the service is
 * having a bad minute" decides whether a connection gets marked {@code ERROR} or quietly retried, and
 * vendors spell that differently. The default recognises the RFC's own {@code invalid_grant}, which
 * covers Google and most others; override where a vendor deviates.
 */
public abstract class AbstractOAuth2Provider implements OAuthProvider {

    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(30);

    protected final OutboundHttp http;

    /**
     * Exists only so CDI can synthesise a no-args constructor on a normal-scoped subclass for its
     * client proxy. The proxy never executes a method against its own fields — every call is delegated
     * to the real instance — so the null here is never read. Without it, {@code @ApplicationScoped} on
     * any subclass fails the build with "not possible to automatically add a synthetic no-args
     * constructor to an unproxyable bean class".
     */
    protected AbstractOAuth2Provider() {
        this.http = null;
    }

    protected AbstractOAuth2Provider(OutboundHttp http) {
        this.http = http;
    }

    /** Consent endpoint the browser is sent to. */
    protected abstract String authorizeEndpoint();

    /** Token endpoint used for both the code exchange and refreshes. */
    protected abstract String tokenEndpoint();

    /**
     * Extra query parameters on the consent URL. This is where a vendor's "and please do give me a
     * refresh token" dialect goes — Google's {@code access_type=offline&prompt=consent}, and whatever
     * the next vendor calls the same thing.
     */
    protected Map<String, String> extraAuthorizeParams() {
        return Map.of();
    }

    /** Separator between scopes in the authorize URL. Space for most; a comma for a few vendors. */
    protected String scopeSeparator() {
        return " ";
    }

    @Override
    public String authorizeUrl(AuthorizeRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", request.client().id());
        params.put("redirect_uri", request.redirectUri());
        params.put("scope", String.join(scopeSeparator(), request.scopes()));
        params.put("state", request.state());
        params.putAll(extraAuthorizeParams());
        return authorizeEndpoint() + "?" + form(params);
    }

    @Override
    public OAuthTokens exchangeCode(String code, String redirectUri, OAuthClient client) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("client_id", client.id());
        params.put("client_secret", client.secret());
        // A user is sitting in front of this waiting for a redirect, and no connection exists yet to
        // carry a quota, so the one-off exchange is not charged to a bucket.
        return post(params, RateLimit.NONE, "code exchange");
    }

    @Override
    public OAuthTokens refresh(String refreshToken, OAuthClient client, RateLimit limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", client.id());
        params.put("client_secret", client.secret());
        return post(params, limit, "token refresh");
    }

    /**
     * Whether this failure means the grant is dead for good rather than momentarily unavailable.
     *
     * @param status HTTP status, or 0 when the call never got a response
     * @param body   response body snippet
     */
    protected boolean isCredentialsRejected(int status, String body) {
        return (status == 400 || status == 401)
                && body != null && body.contains("invalid_grant");
    }

    private OAuthTokens post(Map<String, String> params, RateLimit limit, String what) {
        HttpCall call = HttpCall.post(tokenEndpoint(), form(params), TOKEN_TIMEOUT, limit)
                .acceptJson()
                .header("Content-Type", "application/x-www-form-urlencoded");
        JsonNode json;
        try {
            json = http.json(call);
        } catch (OutboundHttpException e) {
            if (isCredentialsRejected(e.status(), e.bodySnippet())) {
                throw new CredentialsRejectedException(id() + " rejected the sign-in during " + what
                        + " — the account needs reconnecting: " + e.bodySnippet(), e);
            }
            throw new OAuthTransportException(id() + " " + what + " failed"
                    + (e.status() == 0 ? "" : " (HTTP " + e.status() + ")")
                    + (e.bodySnippet() == null || e.bodySnippet().isBlank() ? "" : ": " + e.bodySnippet()), e);
        }
        String accessToken = json.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new OAuthTransportException(id() + " " + what + " returned no access_token");
        }
        return OAuthTokens.expiringIn(accessToken,
                json.path("refresh_token").asText(null),
                json.path("expires_in").asLong(OAuthTokens.DEFAULT_TTL_SECONDS));
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return out.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
