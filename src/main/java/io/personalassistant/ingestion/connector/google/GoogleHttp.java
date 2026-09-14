package io.personalassistant.ingestion.connector.google;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

/**
 * JSON/bytes HTTP for the Gmail and Drive adapters: the shared {@link OutboundHttp} transport plus the
 * two things local to this package — the {@code Authorization} header, and translating a failure into
 * {@link GoogleApiException}, which the connectors and the token refresher already branch on.
 *
 * <p>The {@link GoogleAuth} argument carries the account's quota alongside its token, so two accounts on
 * the same host are throttled independently.
 */
@ApplicationScoped
public class GoogleHttp {

    private final OutboundHttp http;

    @Inject
    public GoogleHttp(OutboundHttp http) {
        this.http = http;
    }

    /** GET a URL and parse the body as JSON. */
    public JsonNode getJson(String url, GoogleAuth auth, long timeoutSeconds) {
        try {
            return http.json(request(url, auth, timeoutSeconds).acceptJson());
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    /** GET a URL and return the raw bytes (file download / export). */
    public byte[] getBytes(String url, GoogleAuth auth, long timeoutSeconds) {
        try {
            return http.bytes(request(url, auth, timeoutSeconds));
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    /** POST a JSON body on behalf of an account and parse the JSON response. */
    public JsonNode postJson(String url, String jsonBody, GoogleAuth auth, long timeoutSeconds) {
        HttpCall call = HttpCall.post(url, jsonBody, Duration.ofSeconds(timeoutSeconds),
                        auth == null ? null : auth.limit())
                .header("Content-Type", "application/json")
                .acceptJson();
        try {
            return http.json(auth == null ? call : call.header("Authorization", "Bearer " + auth.bearer()));
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    /**
     * POST a form body with no credentials and parse the JSON response. For endpoints that take a token
     * as a parameter — it belongs in a body, never in a URL that ends up in logs.
     */
    public JsonNode postForm(String url, String formBody, long timeoutSeconds) {
        try {
            return http.json(HttpCall.post(url, formBody, Duration.ofSeconds(timeoutSeconds), null)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .acceptJson());
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    private static HttpCall request(String url, GoogleAuth auth, long timeoutSeconds) {
        HttpCall call = HttpCall.get(url, Duration.ofSeconds(timeoutSeconds),
                auth == null ? null : auth.limit());
        return auth == null ? call : call.header("Authorization", "Bearer " + auth.bearer());
    }

    private static GoogleApiException translate(OutboundHttpException e) {
        if (e.status() == 0) {
            return new GoogleApiException("Google API request failed for " + e.url(), e);
        }
        return new GoogleApiException(e.status(),
                "Google API " + e.status() + " for " + e.url() + ": " + e.bodySnippet());
    }
}
