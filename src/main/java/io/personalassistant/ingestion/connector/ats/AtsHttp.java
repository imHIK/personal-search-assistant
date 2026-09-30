package io.personalassistant.ingestion.connector.ats;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

@ApplicationScoped
public class AtsHttp {

    private final OutboundHttp http;

    @Inject
    public AtsHttp(OutboundHttp http) {
        this.http = http;
    }

    public JsonNode getJson(String url, long timeoutSeconds, RateLimit limit) {
        return json(HttpCall.get(url, Duration.ofSeconds(timeoutSeconds), limit).acceptJson());
    }

    /** For Workday, whose search is a POST with the paging window in the body. */
    public JsonNode postJson(String url, String body, long timeoutSeconds, RateLimit limit) {
        return json(HttpCall.post(url, body, Duration.ofSeconds(timeoutSeconds), limit)
                .acceptJson()
                .header("Content-Type", "application/json"));
    }

    private JsonNode json(HttpCall call) {
        try {
            return http.json(call);
        } catch (OutboundHttpException e) {
            throw translate(e);
        }
    }

    private static AtsApiException translate(OutboundHttpException e) {
        if (e.status() == 0) {
            return new AtsApiException("ATS request failed for " + e.url(), e);
        }
        return new AtsApiException(e.status(),
                "ATS API " + e.status() + " for " + e.url() + ": " + e.bodySnippet());
    }
}
