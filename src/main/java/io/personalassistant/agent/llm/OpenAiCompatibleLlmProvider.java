package io.personalassistant.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.ProviderImpl;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @ConfigProperty(name = "app.llm.model", defaultValue = "openai/gpt-oss-120b")
    String modelName;

    /** Optional: blank means send no Authorization header (e.g. a local Ollama). See {@link ConfigText}. */
    @ConfigProperty(name = "app.llm.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "app.llm.temperature", defaultValue = "0.2")
    double temperature;

    @ConfigProperty(name = "app.llm.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean configLogged = new AtomicBoolean();
    private final OutboundHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public OpenAiCompatibleLlmProvider(OutboundHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

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
        return complete(LlmProfile.inherit("default"), system, messages);
    }

    /**
     * Applies only the fields the profile actually sets, so an inherit-everything profile produces
     * exactly the request this adapter sent before profiles existed.
     */
    @Override
    public String complete(LlmProfile profile, String system, List<Message> messages) {
        return complete(profile, ResponseFormat.TEXT, system, messages);
    }

    @Override
    public String complete(LlmProfile profile, ResponseFormat format, String system,
                           List<Message> messages) {
        logConfigOnce();
        String endpoint = profile.baseUrl().orElse(baseUrl);
        String model = profile.model().orElse(modelName);
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", profile.temperature().orElse(temperature));
            profile.maxTokens().ifPresent(max -> body.put("max_tokens", max));
            if (format == ResponseFormat.JSON_OBJECT) {
                body.putObject("response_format").put("type", "json_object");
            }
            ArrayNode msgs = body.putArray("messages");
            if (system != null && !system.isBlank()) {
                addMessage(msgs, "system", system);
            }
            for (Message m : messages) {
                addMessage(msgs, m.role(), m.content());
            }

            String key = resolveKey(profile);
            // The profile decides whether a throttled call waits: answering runs on a user's request
            // thread and should degrade to answerError, while a digest runs in the background and should
            // simply take longer. See LlmProfile#rateLimitMode.
            HttpCall call = HttpCall
                    .post(endpoint.replaceAll("/+$", "") + "/chat/completions",
                            mapper.writeValueAsString(body), Duration.ofSeconds(timeoutSeconds),
                            policies.forLlm(providerId(), profile.rateLimitMode().orElse(defaultMode())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", key == null ? null : "Bearer " + key);

            LOG.fine(() -> "LLM request: " + messages.size() + " message(s) -> "
                    + callSummary(profile, endpoint, model, key != null));
            JsonNode content = http.json(call)
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("LLM API returned no choices from " + endpoint);
            }
            return content.asText();
        } catch (RateLimitedException e) {
            throw e;
        } catch (OutboundHttpException e) {
            throw new IllegalStateException("LLM API " + e.status() + ": " + e.bodySnippet() + " ["
                    + callSummary(profile, endpoint, model, resolveKey(profile) != null) + "]", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM request failed (" + endpoint + ")", e);
        }
    }

    /**
     * What a profile that says nothing gets. Fail-fast, because the only caller that reaches the LLM
     * without naming a background profile is answering, on a user's request thread.
     */
    private static RateLimitMode defaultMode() {
        return RateLimitMode.FAIL_FAST;
    }

    /**
     * The credential for this call, or null to send no {@code Authorization} header.
     *
     * <p>A profile that redirects {@code base-url} must bring its own key. Inheriting the provider's
     * would send one vendor's secret to another vendor's host — that is a credential leak, not a
     * convenience, so a redirected profile without a key sends none (which is exactly right for a local
     * Ollama, the main reason to redirect).
     */
    // Package-private for tests.
    String resolveKey(LlmProfile profile) {
        Optional<String> fromProfile = profile.apiKey().filter(k -> !k.isBlank());
        if (profile.baseUrl().isPresent()) {
            return fromProfile.orElse(null);
        }
        return fromProfile.orElse(ConfigText.orNull(apiKey));
    }

    /**
     * What this specific call resolved to, profile included — so a failure names the model that actually
     * failed rather than the provider's default. Never logs the key itself, only whether one resolved.
     */
    // Package-private for tests.
    String callSummary(LlmProfile profile, String endpoint, String model, boolean hasKey) {
        return "profile=" + profile.name() + ", base-url=" + endpoint + ", model=" + model
                + ", temperature=" + profile.temperature().orElse(temperature)
                + ", max-tokens=" + profile.maxTokens().map(String::valueOf).orElse("<unset>")
                + ", api-key=" + (hasKey ? "present" : "ABSENT -> sending no Authorization header");
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

    /** The provider-level defaults every profile inherits from. Never logs the key itself. */
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
}
