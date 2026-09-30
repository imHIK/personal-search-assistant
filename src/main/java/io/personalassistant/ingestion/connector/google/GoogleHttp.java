package io.personalassistant.ingestion.connector.google;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

@ApplicationScoped
public class GoogleHttp {

    private final OutboundHttp http;

    @Inject
    public GoogleHttp(OutboundHttp http) {
        this.http = http;
    }

    public JsonNode getJson(String url, GoogleAuth auth, long timeoutSeconds) {
        try {
            return http.json(request(url, auth, timeoutSeconds).acceptJson());
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    public byte[] getBytes(String url, GoogleAuth auth, long timeoutSeconds) {
        try {
            return http.bytes(request(url, auth, timeoutSeconds));
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

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
     * For endpoints taking a token as a parameter: it belongs in a body, never in a URL that ends up in logs.
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
