package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.personalassistant.agent.llm.LlmProvider.Message;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
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

class OpenAiCompatibleLlmProviderTest {

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
        server.createContext("/chat/completions", exchange -> {
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

    private OpenAiCompatibleLlmProvider provider() {
        OpenAiCompatibleLlmProvider p =
                new OpenAiCompatibleLlmProvider(new OutboundHttp(limiter), RateLimitPolicies.unlimited());
        p.timeoutSeconds = 5;
        return p;
    }

    private LlmProfile profile(Optional<Double> temperature, Optional<Integer> maxTokens, Optional<String> key) {
        return new LlmProfile("answer", "conn_1", "http://localhost:" + server.getAddress().getPort() + "/",
                "llama-3.3-70b-versatile", temperature, maxTokens, key, null, Optional.empty());
    }

    private LlmProfile profile() {
        return profile(Optional.of(0.2), Optional.empty(), Optional.of("secret-key"));
    }

    @Test
    void sendsSystemThenUserMessagesAndReturnsContent() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"The answer is 42 [1].\"}}]}";
        OpenAiCompatibleLlmProvider p = provider();

        String reply = p.complete(profile(), "Answer only from sources.",
                List.of(new Message("user", "What is the answer?")));

        assertEquals("The answer is 42 [1].", reply);

        assertEquals("/chat/completions", capturedPath.get());
        assertEquals("Bearer secret-key", capturedAuth.get());
        JsonNode body = mapper.readTree(capturedBody.get());
        assertEquals("llama-3.3-70b-versatile", body.path("model").asText());
        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9);

        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("Answer only from sources.", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("What is the answer?", messages.get(1).path("content").asText());
    }

    @Test
    void omitsSystemMessageWhenBlank() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        OpenAiCompatibleLlmProvider p = provider();

        p.complete(profile(), "  ", List.of(new Message("user", "hi")));

        JsonNode messages = mapper.readTree(capturedBody.get()).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
    }

    @Test
    void emptyChoicesThrows() {
        responseJson = "{\"choices\":[]}";
        OpenAiCompatibleLlmProvider p = provider();

        assertThrows(IllegalStateException.class,
                () -> p.complete(profile(), "s", List.of(new Message("user", "hi"))));
    }

    @Test
    void nonSuccessStatusThrowsWithStatusCode() {
        status = 500;
        responseJson = "{\"error\":\"boom\"}";
        OpenAiCompatibleLlmProvider p = provider();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.complete(profile(), "s", List.of(new Message("user", "hi"))));
        assertTrue(ex.getMessage().contains("500"), ex.getMessage());
    }

    @Test
    void aRateLimitedCallPropagatesTheLimiterExceptionUnwrapped() {
        limiter.failWith = new RateLimitedException(RateLimitKey.connection("conn_1"),
                Instant.now().plusSeconds(60));

        RateLimitedException e = assertThrows(RateLimitedException.class,
                () -> provider().complete(profile(), "sys", List.of(new Message("user", "hi"))));
        assertNotNull(e.retryAt());
    }

    @Test
    void blankTemperatureMaxTokensAndKeyAreLeftOutOfTheRequest() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

        provider().complete(profile(Optional.empty(), Optional.empty(), Optional.empty()), "s",
                List.of(new Message("user", "hi")));

        JsonNode body = mapper.readTree(capturedBody.get());
        assertFalse(body.has("temperature"), "the vendor default applies");
        assertFalse(body.has("max_tokens"));
        assertNull(capturedAuth.get(), "no key sends no Authorization header (a local Ollama)");
    }

    @Test
    void sendsMaxTokensAndJsonModeWhenAsked() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}";

        provider().complete(profile(Optional.empty(), Optional.of(4096), Optional.empty()),
                LlmProvider.ResponseFormat.JSON_OBJECT, "s", List.of(new Message("user", "hi")));

        JsonNode body = mapper.readTree(capturedBody.get());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void eachCallIsChargedToItsConnection() {
        assertEquals(RateLimitKey.connection("conn_1"), provider().rateLimit(profile()).key());
    }

    @Test
    void anErrorNamesTheCallButNeverTheKey() {
        status = 401;
        responseJson = "{\"error\":\"bad key\"}";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider().complete(profile(), "s", List.of(new Message("user", "hi"))));

        assertTrue(e.getMessage().contains("connection=conn_1"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-key"), "the key must never reach a log or an error");
    }

    @Test
    void theStubProviderRefuses() {
        assertThrows(UnsupportedOperationException.class,
                () -> new StubLlmProvider().complete(profile(), "sys", List.of()));
    }
}
