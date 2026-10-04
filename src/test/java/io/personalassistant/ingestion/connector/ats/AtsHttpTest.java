package io.personalassistant.ingestion.connector.ats;

import com.sun.net.httpserver.HttpServer;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.testsupport.RecordingRateLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AtsHttpTest {

    private static final RateLimit LIMIT =
            new RateLimit(RateLimitKey.board("eightfold"), RateLimitPolicy.UNLIMITED, RateLimitMode.WAIT);

    private HttpServer server;
    private final RecordingRateLimiter limiter = new RecordingRateLimiter();
    private volatile int status;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jobs", exchange -> {
            byte[] out = "Please try again later".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "120");
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

    private String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/jobs";
    }

    @Test
    void aBoardsTooManyRequestsDefersOnThatBoardsBucket() {
        status = 429;
        AtsHttp http = new AtsHttp(new OutboundHttp(limiter));

        RateLimitedException e = Assertions.assertThrows(RateLimitedException.class,
                () -> http.getJson(url(), 5, LIMIT));

        Assertions.assertEquals(LIMIT.key(), e.key());
        Assertions.assertEquals(limiter.penalties.get(LIMIT.key().value()), e.retryAt(),
                "the cursor resumes when the bucket does");
        Assertions.assertTrue(e.retryAt().isAfter(Instant.now().plusSeconds(100)));
    }

    @Test
    void theCareersPageReadDefersToo() {
        status = 429;

        Assertions.assertThrows(RateLimitedException.class,
                () -> new AtsHttp(new OutboundHttp(limiter)).getText(url(), 5, LIMIT));
    }

    @Test
    void anyOtherErrorStillFails() {
        status = 503;

        AtsApiException e = Assertions.assertThrows(AtsApiException.class,
                () -> new AtsHttp(new OutboundHttp(limiter)).getJson(url(), 5, LIMIT));

        Assertions.assertTrue(limiter.penalties.isEmpty());
        Assertions.assertTrue(e.getMessage().contains("503"), e.getMessage());
    }
}
