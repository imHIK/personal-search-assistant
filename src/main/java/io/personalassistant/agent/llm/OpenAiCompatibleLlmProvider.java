package io.personalassistant.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Chat completion over the OpenAI-compatible {@code POST {base-url}/chat/completions} schema. One
 * adapter covers every provider that speaks it — Groq, Google Gemini, Mistral, OpenRouter, Together and
 * a local Ollama server — so switching between hosted and local is config only (base-url + model +
 * api-key), with no code change. Selected with {@code app.llm.provider=openai-compat}.
 *
 * <p>Defaults target Groq's free tier ({@code llama-3.3-70b-versatile}); set the api key from an env var
 * via {@code app.llm.api-key=${GROQ_API_KEY:}}. To run locally later, point {@code app.llm.base-url} at
 * {@code http://localhost:11434/v1} (Ollama) and set {@code app.llm.model} — no other changes.
 */
@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleLlmProvider.class.getName());

    @ConfigProperty(name = "app.llm.base-url", defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "app.llm.model", defaultValue = "llama-3.3-70b-versatile")
    String modelName;

    /** Optional: blank means send no Authorization header (e.g. a local Ollama). See {@link ConfigText}. */
    @ConfigProperty(name = "app.llm.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "app.llm.temperature", defaultValue = "0.2")
    double temperature;

    @ConfigProperty(name = "app.llm.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final AtomicBoolean configLogged = new AtomicBoolean();

    @Override
    public String providerId() {
        return "openai-compat";
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public String complete(String system, List<Message> messages) {
        logConfigOnce();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
            body.put("temperature", temperature);
            ArrayNode msgs = body.putArray("messages");
            if (system != null && !system.isBlank()) {
                addMessage(msgs, "system", system);
            }
            for (Message m : messages) {
                addMessage(msgs, m.role(), m.content());
            }

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            String key = ConfigText.orNull(apiKey);
            if (key != null) {
                request.header("Authorization", "Bearer " + key);
            }

            LOG.fine(() -> "LLM request: " + messages.size() + " message(s) -> " + configSummary());
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // See the embedding provider: a missing key is reported by vendors as anything from
                // 400 to 404, so the resolved config belongs in the message rather than the logs only.
                throw new IllegalStateException("LLM API " + response.statusCode() + ": "
                        + snippet(response.body()) + " [" + configSummary() + "]");
            }

            JsonNode content = mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("LLM API returned no choices: " + snippet(response.body()));
            }
            return content.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM request failed (" + baseUrl + ")", e);
        }
    }

    /**
     * One INFO line, on first use, naming what this provider resolved to — see the embedding
     * provider for why a blank api-key is legitimate (local Ollama) and therefore not fatal here.
     */
    private void logConfigOnce() {
        if (configLogged.compareAndSet(false, true)) {
            LOG.info("LLM provider ready: " + configSummary());
        }
    }

    /** Never logs the key itself — only whether one resolved. */
    private String configSummary() {
        return "base-url=" + baseUrl + ", model=" + modelName + ", temperature=" + temperature
                + ", api-key=" + (ConfigText.orNull(apiKey) == null
                        ? "ABSENT -> sending no Authorization header" : "present");
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode node = messages.addObject();
        node.put("role", role);
        node.put("content", content);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
