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

@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleLlmProvider.class.getName());

    @ConfigProperty(name = "app.llm.base-url", defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "app.llm.model", defaultValue = "openai/gpt-oss-120b")
    String modelName;

    /** Blank sends no Authorization header (a local Ollama). */
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

    private static RateLimitMode defaultMode() {
        return RateLimitMode.FAIL_FAST;
    }

    /**
     * A profile that redirects base-url never inherits the provider's key: that would send one vendor's
     * secret to another vendor's host.
     */
    String resolveKey(LlmProfile profile) {
        Optional<String> fromProfile = profile.apiKey().filter(k -> !k.isBlank());
        if (profile.baseUrl().isPresent()) {
            return fromProfile.orElse(null);
        }
        return fromProfile.orElse(ConfigText.orNull(apiKey));
    }

    /** Never includes the key, only whether one resolved. */
    String callSummary(LlmProfile profile, String endpoint, String model, boolean hasKey) {
        return "profile=" + profile.name() + ", base-url=" + endpoint + ", model=" + model
                + ", temperature=" + profile.temperature().orElse(temperature)
                + ", max-tokens=" + profile.maxTokens().map(String::valueOf).orElse("<unset>")
                + ", api-key=" + (hasKey ? "present" : "ABSENT -> sending no Authorization header");
    }

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
