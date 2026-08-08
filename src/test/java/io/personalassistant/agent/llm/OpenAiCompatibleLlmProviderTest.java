package io.personalassistant.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.personalassistant.agent.llm.LlmProvider.Message;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the OpenAI-compatible chat adapter builds the request the schema expects (model, temperature,
 * system-then-user messages, bearer auth) and extracts the reply from the response — against a local stub
 * {@link HttpServer}, no network.
 */
class OpenAiCompatibleLlmProviderTest {

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
        OpenAiCompatibleLlmProvider p = new OpenAiCompatibleLlmProvider();
        p.baseUrl = "http://localhost:" + server.getAddress().getPort();
        p.modelName = "llama-3.3-70b-versatile";
        p.apiKey = Optional.of("secret-key");
        p.temperature = 0.2;
        p.timeoutSeconds = 5;
        return p;
    }

    @Test
    void sendsSystemThenUserMessagesAndReturnsContent() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"The answer is 42 [1].\"}}]}";
        OpenAiCompatibleLlmProvider p = provider();

        String reply = p.complete("Answer only from sources.",
                List.of(new Message("user", "What is the answer?")));

        assertEquals("The answer is 42 [1].", reply);

        // Request assertions.
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

        p.complete("  ", List.of(new Message("user", "hi")));

        JsonNode messages = mapper.readTree(capturedBody.get()).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
    }

    @Test
    void emptyChoicesThrows() {
        responseJson = "{\"choices\":[]}";
        OpenAiCompatibleLlmProvider p = provider();

        assertThrows(IllegalStateException.class,
                () -> p.complete("s", List.of(new Message("user", "hi"))));
    }

    @Test
    void nonSuccessStatusThrowsWithStatusCode() {
        status = 500;
        responseJson = "{\"error\":\"boom\"}";
        OpenAiCompatibleLlmProvider p = provider();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.complete("s", List.of(new Message("user", "hi"))));
        assertTrue(ex.getMessage().contains("500"), ex.getMessage());
    }
}
