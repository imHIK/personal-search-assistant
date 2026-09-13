package io.personalassistant.ingestion.connector.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.oauth.AuthorizeRequest;
import io.personalassistant.ingestion.connector.oauth.CredentialsRejectedException;
import io.personalassistant.ingestion.connector.oauth.OAuthClient;
import io.personalassistant.ingestion.connector.oauth.OAuthTokens;
import io.personalassistant.ingestion.connector.oauth.OAuthTransportException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The Google-specific half: the consent parameters that decide whether a reconnect actually yields a
 * refresh token, and the one judgement the generic layer cannot make — telling a permanently dead
 * grant apart from a bad minute at Google.
 */
class GoogleOAuthProviderTest {

    private static final OAuthClient CLIENT = new OAuthClient("client-id", "client-secret");

    @Test
    void asksForAnOfflineGrantAndForcesReconsent() {
        String url = provider(call -> {
            throw new IllegalStateException("no HTTP expected");
        }).authorizeUrl(request(SourceType.GMAIL));

        Assertions.assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url);
        Assertions.assertTrue(url.contains("access_type=offline"), url);
        // Without prompt=consent a user reconnecting a broken account gets an access token and no
        // refresh token, and is back where they started an hour later.
        Assertions.assertTrue(url.contains("prompt=consent"), url);
        Assertions.assertTrue(url.contains("include_granted_scopes=true"), url);
        Assertions.assertTrue(url.contains("response_type=code"), url);
        Assertions.assertTrue(url.contains("state=the-state"), url);
    }

    @Test
    void requestsTheReadOnlyScopeForEachConnector() {
        GoogleOAuthProvider provider = provider(call -> {
            throw new IllegalStateException("no HTTP expected");
        });

        Assertions.assertEquals(Set.of("https://www.googleapis.com/auth/gmail.readonly"),
                provider.scopesFor("GMAIL"));
        Assertions.assertEquals(Set.of("https://www.googleapis.com/auth/drive.readonly"),
                provider.scopesFor("GOOGLE_DRIVE"));
        // Its own type, so the sending account never holds a read scope and the reading one never sends.
        Assertions.assertEquals(Set.of("https://www.googleapis.com/auth/gmail.send"),
                provider.scopesFor(GoogleConnectionTypes.GMAIL_SEND));
        Assertions.assertTrue(provider.supports().contains(GoogleConnectionTypes.GMAIL_SEND));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> provider.scopesFor("LOCAL_FS"));
    }

    @Test
    void putsTheScopeAndRedirectUriInTheConsentUrl() {
        String url = provider(call -> {
            throw new IllegalStateException("no HTTP expected");
        }).authorizeUrl(request(SourceType.GOOGLE_DRIVE));
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);

        Assertions.assertTrue(decoded.contains("scope=https://www.googleapis.com/auth/drive.readonly"),
                decoded);
        Assertions.assertTrue(
                decoded.contains("redirect_uri=http://localhost:5173/api/connections/oauth/google/callback"),
                decoded);
    }

    @Test
    void exchangesACodeForTokens() throws Exception {
        JsonNode response = new ObjectMapper().readTree(
                "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}");

        OAuthTokens tokens = provider(call -> response)
                .exchangeCode("the-code", "http://localhost:8080/cb", CLIENT);

        Assertions.assertEquals("at", tokens.accessToken());
        Assertions.assertEquals("rt", tokens.refreshToken());
        Assertions.assertTrue(tokens.expiresAtEpochSec() > java.time.Instant.now().getEpochSecond());
    }

    @Test
    void treatsInvalidGrantAsDeadCredentials() {
        GoogleOAuthProvider provider = provider(call -> {
            throw new OutboundHttpException(400, call.url(),
                    "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}",
                    "HTTP 400");
        });

        Assertions.assertThrows(CredentialsRejectedException.class,
                () -> provider.refresh("revoked", CLIENT, RateLimit.NONE));
    }

    @Test
    void treatsAServerErrorAsTransient() {
        GoogleOAuthProvider provider = provider(call -> {
            throw new OutboundHttpException(503, call.url(), "backend error", "HTTP 503");
        });

        // The distinction is the whole point: this must not mark the connection ERROR.
        Assertions.assertThrows(OAuthTransportException.class,
                () -> provider.refresh("fine", CLIENT, RateLimit.NONE));
    }

    @Test
    void treatsAResponseWithoutAnAccessTokenAsTransient() throws Exception {
        JsonNode response = new ObjectMapper().readTree("{\"token_type\":\"Bearer\"}");

        Assertions.assertThrows(OAuthTransportException.class,
                () -> provider(call -> response).refresh("fine", CLIENT, RateLimit.NONE));
    }

    private static AuthorizeRequest request(SourceType type) {
        return new AuthorizeRequest(type.name(), CLIENT,
                "http://localhost:5173/api/connections/oauth/google/callback", "the-state",
                type == SourceType.GMAIL
                        ? Set.of("https://www.googleapis.com/auth/gmail.readonly")
                        : Set.of("https://www.googleapis.com/auth/drive.readonly"));
    }

    /** A provider whose only outside contact is the supplied function. No network, no CDI. */
    private static GoogleOAuthProvider provider(Function<HttpCall, JsonNode> onJson) {
        GoogleOAuthProvider provider = new GoogleOAuthProvider(new OutboundHttp(null) {
            @Override
            public JsonNode json(HttpCall call) {
                return onJson.apply(call);
            }
        });
        provider.authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth";
        provider.tokenUrl = "https://oauth2.googleapis.com/token";
        return provider;
    }
}
