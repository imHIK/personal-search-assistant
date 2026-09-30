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
 * The RFC 6749 half: an authorization-code URL and a form-encoded token endpoint. Subclasses supply the
 * endpoints, scopes and vendor parameters. isCredentialsRejected decides between marking a connection ERROR
 * and retrying; the default recognises {@code invalid_grant}.
 */
public abstract class AbstractOAuth2Provider implements OAuthProvider {

    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(30);

    protected final OutboundHttp http;

    /**
     * Only so CDI can synthesise a no-args constructor for a normal-scoped subclass's client proxy; the proxy
     * never reads these fields.
     */
    protected AbstractOAuth2Provider() {
        this.http = null;
    }

    protected AbstractOAuth2Provider(OutboundHttp http) {
        this.http = http;
    }

    protected abstract String authorizeEndpoint();

    protected abstract String tokenEndpoint();

    /** Where a vendor's "please return a refresh token" dialect goes. */
    protected Map<String, String> extraAuthorizeParams() {
        return Map.of();
    }

    /** A space for most vendors; a comma for a few. */
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
        // A user is waiting and no connection exists yet to carry a quota, so the exchange is not charged.
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
     * Dead for good, as opposed to momentarily unavailable.
     *
     * @param status 0 when the call never got a response
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
