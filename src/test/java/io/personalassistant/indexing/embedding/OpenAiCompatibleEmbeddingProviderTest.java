package io.personalassistant.indexing.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.testsupport.RecordingRateLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingProviderTest {

    private final RecordingRateLimiter limiter = new RecordingRateLimiter();
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseJson = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/embeddings", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
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

    private OpenAiCompatibleEmbeddingProvider provider(int dim) {
        OpenAiCompatibleEmbeddingProvider p = new OpenAiCompatibleEmbeddingProvider(new OutboundHttp(limiter), RateLimitPolicies.unlimited());
        p.baseUrl = "http://localhost:" + server.getAddress().getPort();
        p.modelName = "text-embedding-004";
        p.apiKey = Optional.of("secret-key");
        p.dimension = dim;
        p.timeoutSeconds = 5;
        return p;
    }

    @Test
    void sendsModelAndInputArrayAndAuthHeader() throws Exception {
        responseJson = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(4);

        Embedding e = p.embed("hello world");

        assertEquals("/embeddings", capturedPath.get());
        assertEquals("Bearer secret-key", capturedAuth.get());
        JsonNode body = mapper.readTree(capturedBody.get());
        assertEquals("text-embedding-004", body.path("model").asText());
        assertTrue(body.path("input").isArray());
        assertEquals(1, body.path("input").size());
        assertEquals("hello world", body.path("input").get(0).asText());

        assertEquals("text-embedding-004", e.model());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.4f}, e.vector(), 1e-6f);
    }

    @Test
    void reordersVectorsByReportedIndex() {
        responseJson = "{\"data\":["
                + "{\"index\":1,\"embedding\":[9,9]},"
                + "{\"index\":0,\"embedding\":[1,1]}"
                + "]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        List<Embedding> out = p.embedAll(List.of("first", "second"));

        assertEquals(2, out.size());
        assertArrayEquals(new float[] {1f, 1f}, out.get(0).vector(), 1e-6f);
        assertArrayEquals(new float[] {9f, 9f}, out.get(1).vector(), 1e-6f);
    }

    @Test
    void mismatchedVectorWidthIsRejected() {
        responseJson = "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        assertThrows(IllegalStateException.class, () -> p.embed("x"));
    }

    @Test
    void duplicateReportedIndexIsRejectedRatherThanLeavingAHole() {
        responseJson = "{\"data\":["
                + "{\"index\":0,\"embedding\":[1,1]},"
                + "{\"index\":0,\"embedding\":[2,2]}"
                + "]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.embedAll(List.of("first", "second")));
        assertTrue(ex.getMessage().contains("twice"), ex.getMessage());
    }

    /**
     * Gemini's real {@code /embeddings} shape: proto3 omits default values, so the first item has no
     * {@code index}.
     */
    @Test
    void handlesGeminiOmittingIndexOnTheFirstItem() {
        responseJson = "{\"data\":["
                + "{\"object\":\"embedding\",\"embedding\":[1,1]},"
                + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[2,2]},"
                + "{\"object\":\"embedding\",\"index\":2,\"embedding\":[3,3]}"
                + "]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        List<Embedding> out = p.embedAll(List.of("first", "second", "third"));

        assertEquals(3, out.size());
        assertArrayEquals(new float[] {1f, 1f}, out.get(0).vector(), 1e-6f);
        assertArrayEquals(new float[] {2f, 2f}, out.get(1).vector(), 1e-6f);
        assertArrayEquals(new float[] {3f, 3f}, out.get(2).vector(), 1e-6f);
    }

    @Test
    void missingIndexFieldFallsBackToPayloadOrder() {
        responseJson = "{\"data\":[{\"embedding\":[1,1]},{\"embedding\":[2,2]},{\"embedding\":[3,3]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        List<Embedding> out = p.embedAll(List.of("first", "second", "third"));

        assertEquals(3, out.size());
        assertArrayEquals(new float[] {1f, 1f}, out.get(0).vector(), 1e-6f);
        assertArrayEquals(new float[] {3f, 3f}, out.get(2).vector(), 1e-6f);
    }

    @Test
    void singleEmbeddingWithoutIndexFieldWorks() {
        responseJson = "{\"data\":[{\"embedding\":[0.5,0.5]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        assertArrayEquals(new float[] {0.5f, 0.5f}, p.embed("hello").vector(), 1e-6f);
    }

    @Test
    void outOfRangeIndexIsRejected() {
        responseJson = "{\"data\":[{\"index\":7,\"embedding\":[1,1]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(2);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> p.embedAll(List.of("only")));
        assertTrue(ex.getMessage().contains("7"), ex.getMessage());
    }

    @Test
    void nonSuccessStatusThrowsWithStatusCode() {
        status = 429;
        responseJson = "{\"error\":\"rate limited\"}";
        OpenAiCompatibleEmbeddingProvider p = provider(4);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> p.embed("x"));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("429"), ex.getMessage());
    }

    @Test
    void aThrottledResponsePausesTheEmbeddingQuota() {
        status = 429;
        responseJson = "{\"error\":\"rate limited\"}";

        assertThrows(IllegalStateException.class, () -> provider(4).embed("x"));

        assertTrue(limiter.penalties.containsKey("embedding:openai-embed"),
                "expected the embedding bucket to be paused, saw " + limiter.penalties.keySet());
    }

    @Test
    void aRateLimitedCallPropagatesTheLimiterExceptionUnwrapped() {
        limiter.failWith = new RateLimitedException(RateLimitKey.embedding("openai-embed"),
                Instant.now().plusSeconds(120));
        OpenAiCompatibleEmbeddingProvider p = provider(4);

        RateLimitedException e =
                assertThrows(RateLimitedException.class, () -> p.embedAll(List.of("doc")));
        assertNotNull(e.retryAt());
    }

    @Test
    void indexingWaitsForQuotaWhileASearchQueryFailsFast() {
        responseJson = "{\"data\":[{\"index\":0,\"embedding\":[1,0,0,0]}]}";
        OpenAiCompatibleEmbeddingProvider p = provider(4);

        p.embedAll(List.of("a document"));
        assertEquals(RateLimitMode.WAIT, limiter.acquired.get(0).mode());

        p.embedQuery("a search");
        assertEquals(RateLimitMode.FAIL_FAST, limiter.acquired.get(1).mode());
    }
}
