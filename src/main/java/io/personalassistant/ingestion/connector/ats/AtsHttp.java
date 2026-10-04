package io.personalassistant.ingestion.connector.ats;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class AtsHttp {

    private final OutboundHttp http;

    @Inject
    public AtsHttp(OutboundHttp http) {
        this.http = http;
    }

    public JsonNode getJson(String url, long timeoutSeconds, RateLimit limit) {
        return getJson(url, Map.of(), timeoutSeconds, limit);
    }

    /** For TurboHire, which wants a Referer and a bearer token. */
    public JsonNode getJson(String url, Map<String, String> headers, long timeoutSeconds, RateLimit limit) {
        HttpCall call = HttpCall.get(url, Duration.ofSeconds(timeoutSeconds), limit).acceptJson();
        return json(withHeaders(call, headers));
    }

    /** For Workday, whose search is a POST with the paging window in the body. */
    public JsonNode postJson(String url, String body, long timeoutSeconds, RateLimit limit) {
        return postJson(url, body, Map.of(), timeoutSeconds, limit);
    }

    public JsonNode postJson(String url, String body, Map<String, String> headers, long timeoutSeconds,
                             RateLimit limit) {
        return json(withHeaders(HttpCall.post(url, body, Duration.ofSeconds(timeoutSeconds), limit)
                .acceptJson()
                .header("Content-Type", "application/json"), headers));
    }

    private static HttpCall withHeaders(HttpCall call, Map<String, String> headers) {
        HttpCall out = call;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            out = out.header(header.getKey(), header.getValue());
        }
        return out;
    }

    /** For Zwayam, whose search takes form fields. */
    public JsonNode postForm(String url, Map<String, String> fields, long timeoutSeconds, RateLimit limit) {
        String body = fields.entrySet().stream()
                .map(f -> encode(f.getKey()) + "=" + encode(f.getValue()))
                .collect(Collectors.joining("&"));
        return json(HttpCall.post(url, body, Duration.ofSeconds(timeoutSeconds), limit)
                .acceptJson()
                .header("Content-Type", "application/x-www-form-urlencoded"));
    }

    /** For Keka, whose board id is only in the careers page. */
    public String getText(String url, long timeoutSeconds, RateLimit limit) {
        HttpCall call = HttpCall.get(url, Duration.ofSeconds(timeoutSeconds), limit);
        try {
            return new String(http.bytes(call), StandardCharsets.UTF_8);
        } catch (OutboundHttpException e) {
            throw translate(e, call);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode json(HttpCall call) {
        try {
            return http.json(call);
        } catch (OutboundHttpException e) {
            throw translate(e, call);
        }
    }

    /**
     * A board's 429 becomes a deferral on the board's bucket, which the runner holds rather than counting
     * against the retry limit.
     */
    private static RuntimeException translate(OutboundHttpException e, HttpCall call) {
        if (e.retryAt() != null) {
            return new RateLimitedException(call.limit().key(), e.retryAt());
        }
        if (e.status() == 0) {
            return new AtsApiException("ATS request failed for " + e.url(), e);
        }
        return new AtsApiException(e.status(),
                "ATS API " + e.status() + " for " + e.url() + ": " + e.bodySnippet());
    }
}
