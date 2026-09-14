package io.personalassistant.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place this application talks to the outside world over HTTP.
 *
 * <p>It exists to create a choke point that did not previously exist. Before, four near-identical
 * transports each built their own {@link HttpClient} — and the ATS one built a fresh client, with its own
 * selector thread and executor, on <em>every request</em>. Collapsing them gives a single pooled client
 * and, more importantly, a single point where a quota can be enforced and a {@code 429} observed.
 *
 * <p>Observing the {@code 429} is the part no other layer can do. A limit derived only from local
 * counters is a guess; {@code Retry-After} is the server stating its own capacity, and feeding it back
 * into the limiter is what stops the next caller from hammering a service that just said stop.
 *
 * <p>Failures surface as {@link OutboundHttpException} — deliberately provider-neutral, so the per-area
 * facades can map it onto the exception types their connectors already branch on.
 */
@ApplicationScoped
public class OutboundHttp {

    private static final int SNIPPET_CHARS = 300;
    private static final int TOO_MANY_REQUESTS = 429;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final RateLimiter limiter;

    /**
     * Pause applied when a {@code 429} carries no usable {@code Retry-After}. Without it a server that
     * throttles without explaining itself would be retried at full speed.
     */
    @ConfigProperty(name = "app.ratelimit.default-retry-after-seconds", defaultValue = "60")
    long defaultRetryAfterSeconds;

    @Inject
    public OutboundHttp(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Perform {@code call} and parse the response body as JSON. */
    public JsonNode json(HttpCall call) {
        HttpResponse<String> response = send(call, HttpResponse.BodyHandlers.ofString());
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new OutboundHttpException(call.url(), "Failed to parse JSON from " + call.url(), e);
        }
    }

    /** Perform {@code call} and return the raw response body (file download / export). */
    public byte[] bytes(HttpCall call) {
        return send(call, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    /**
     * Charge the quota, send the request, and translate a non-2xx into
     * {@link OutboundHttpException} — feeding a {@code 429}'s {@code Retry-After} back to the limiter on
     * the way out.
     *
     * @throws io.personalassistant.common.ratelimit.RateLimitedException if the quota is exhausted and
     *                                                                    the call's mode forbids waiting
     */
    public <T> HttpResponse<T> send(HttpCall call, HttpResponse.BodyHandler<T> handler) {
        limiter.acquire(call.limit());
        HttpResponse<T> response;
        try {
            response = http.send(build(call), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboundHttpException(call.url(), "Interrupted calling " + call.url(), e);
        } catch (Exception e) {
            throw new OutboundHttpException(call.url(), "HTTP request failed for " + call.url(), e);
        }
        if (response.statusCode() / 100 != 2) {
            String snippet = snippet(response.body());
            if (response.statusCode() == TOO_MANY_REQUESTS) {
                penalize(call.limit(), response);
            }
            throw new OutboundHttpException(response.statusCode(), call.url(), snippet,
                    "HTTP " + response.statusCode() + " for " + call.url() + ": " + snippet);
        }
        return response;
    }

    private HttpRequest build(HttpCall call) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(call.url()));
        if (call.timeout() != null) {
            builder.timeout(call.timeout());
        }
        for (Map.Entry<String, String> header : call.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest.BodyPublisher body = call.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(call.body());
        return builder.method(call.method(), body).build();
    }

    private void penalize(RateLimit limit, HttpResponse<?> response) {
        Instant until = response.headers().firstValue("Retry-After")
                .map(this::parseRetryAfter)
                .orElseGet(() -> Instant.now().plusSeconds(defaultRetryAfterSeconds));
        limiter.penalize(limit.key(), until);
    }

    /**
     * {@code Retry-After} is either delta-seconds or an HTTP-date (RFC 9110 §10.2.3); both are seen in
     * the wild, so both are accepted and an unparseable value falls back to the configured pause.
     */
    private Instant parseRetryAfter(String value) {
        String trimmed = value == null ? "" : value.trim();
        try {
            return Instant.now().plusSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException notSeconds) {
            try {
                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed, Instant::from);
            } catch (Exception notADate) {
                return Instant.now().plusSeconds(defaultRetryAfterSeconds);
            }
        }
    }

    private static String snippet(Object body) {
        if (body == null) {
            return "";
        }
        String text = body instanceof byte[] bytes ? new String(bytes) : body.toString();
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    }
}
