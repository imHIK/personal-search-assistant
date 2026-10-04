package io.personalassistant.common.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.testsupport.RecordingRateLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundHttpTest {

    private static final RateLimitKey KEY = RateLimitKey.board("greenhouse");

    private HttpServer server;
    private final RecordingRateLimiter limiter = new RecordingRateLimiter();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"ok\":true}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jobs", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            if (retryAfter.get() != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter.get());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OutboundHttp http() {
        OutboundHttp http = new OutboundHttp(limiter);
        http.defaultRetryAfterSeconds = 90;
        return http;
    }

    private HttpCall call() {
        return HttpCall.get("http://localhost:" + server.getAddress().getPort() + "/jobs",
                        Duration.ofSeconds(5),
                        new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.WAIT))
                .acceptJson();
    }

    @Test
    void chargesTheQuotaBeforeSendingAndReturnsTheParsedBody() {
        assertTrue(http().json(call()).path("ok").asBoolean());

        assertEquals(1, limiter.acquired.size(), "every request passes the limiter");
        assertEquals(KEY, limiter.acquired.get(0).key());
    }

    @Test
    void aRefusedQuotaMeansNoRequestIsSent() {
        limiter.failWith = new RateLimitedException(KEY, Instant.now().plusSeconds(5));

        assertThrows(RateLimitedException.class, () -> http().json(call()));
    }

    @Test
    void translatesNonSuccessIntoOutboundHttpException() {
        status = 404;
        body = "not found";

        OutboundHttpException e = assertThrows(OutboundHttpException.class, () -> http().json(call()));

        assertEquals(404, e.status());
        assertTrue(e.isNotFound());
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertTrue(limiter.penalties.isEmpty(), "only a 429 penalizes");
    }

    @Test
    void feedsADeltaSecondsRetryAfterBackIntoTheLimiter() {
        status = 429;
        retryAfter.set("120");
        Instant before = Instant.now();

        assertThrows(OutboundHttpException.class, () -> http().json(call()));

        Instant until = limiter.penalties.get(KEY.value());
        assertNotNull(until, "a 429 must pause the bucket");
        assertTrue(until.isAfter(before.plusSeconds(115)), "expected ~120s pause, got " + until);
    }

    @Test
    void feedsAnHttpDateRetryAfterBackIntoTheLimiter() {
        status = 429;
        retryAfter.set("Wed, 21 Oct 2026 07:28:00 GMT");

        assertThrows(OutboundHttpException.class, () -> http().json(call()));

        assertEquals(Instant.parse("2026-10-21T07:28:00Z"), limiter.penalties.get(KEY.value()));
    }

    @Test
    void fallsBackToTheConfiguredPauseWhenRetryAfterIsMissingOrUnreadable() {
        status = 429;
        retryAfter.set("soon-ish");
        Instant before = Instant.now();

        assertThrows(OutboundHttpException.class, () -> http().json(call()));

        Instant until = limiter.penalties.get(KEY.value());
        assertTrue(until.isAfter(before.plusSeconds(85)), "expected the 90s default, got " + until);
    }

    @Test
    void aTooManyRequestsCarriesThePauseTheLimiterActuallyApplied() {
        Instant clamped = Instant.parse("2026-10-03T21:00:00Z");
        OutboundHttp http = new OutboundHttp(new RecordingRateLimiter() {
            @Override
            public Instant penalize(RateLimitKey key, Instant until) {
                super.penalize(key, until);
                return clamped;
            }
        });
        status = 429;
        retryAfter.set("86400");

        OutboundHttpException e = assertThrows(OutboundHttpException.class, () -> http.json(call()));

        assertEquals(clamped, e.retryAt(), "the clamped instant, not the server's raw answer");
    }

    @Test
    void onlyATooManyRequestsCarriesARetryInstant() {
        status = 503;

        OutboundHttpException e = assertThrows(OutboundHttpException.class, () -> http().json(call()));

        assertEquals(null, e.retryAt());
    }

    @Test
    void reportsATransportFailureWithNoStatus() {
        HttpCall unreachable = HttpCall.get("http://localhost:1/nope", Duration.ofSeconds(2),
                new RateLimit(KEY, RateLimitPolicy.UNLIMITED, RateLimitMode.WAIT));

        OutboundHttpException e = assertThrows(OutboundHttpException.class, () -> http().json(unreachable));

        assertEquals(0, e.status());
    }
}
