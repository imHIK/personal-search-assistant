package io.personalassistant.ingestion.connector.ats;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

/**
 * JSON-over-HTTP for the ATS board adapters: the shared {@link OutboundHttp} transport plus the one
 * thing that is local to this package — translating a failure into {@link AtsApiException}, whose
 * {@code isNotFound()} the board resolution path branches on.
 *
 * <p>It used to own an {@link java.net.http.HttpClient} and be constructed per call, which meant a new
 * client (selector thread and executor) for every request. It is now a bean over the shared client, and
 * every call names the quota it is charged against — for these public boards that is the platform, since
 * there is no {@code Connection} to hang a limit on.
 */
@ApplicationScoped
public class AtsHttp {

    private final OutboundHttp http;

    @Inject
    public AtsHttp(OutboundHttp http) {
        this.http = http;
    }

    /** GET a URL and parse the body as JSON. */
    public JsonNode getJson(String url, long timeoutSeconds, RateLimit limit) {
        return json(HttpCall.get(url, Duration.ofSeconds(timeoutSeconds), limit).acceptJson());
    }

    /**
     * POST a JSON body and parse the reply as JSON.
     *
     * <p>Here for Workday, whose job search is a POST with the paging window in the body rather than a
     * query string. Everything else about the call — timeout, quota, non-2xx translation — is identical.
     */
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
