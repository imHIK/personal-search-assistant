package io.personalassistant.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.common.ProviderImpl;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Endpoint, key and model come from the profile's LLM connection on every call; nothing is configured here. */
@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleLlmProvider.class.getName());

    @ConfigProperty(name = "app.llm.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
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
    public String complete(LlmProfile profile, ResponseFormat format, String system, List<Message> messages) {
        String key = profile.apiKey().filter(k -> !k.isBlank()).orElse(null);
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", profile.model());
            profile.temperature().ifPresent(t -> body.put("temperature", t));
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

            HttpCall call = HttpCall
                    .post(profile.baseUrl().replaceAll("/+$", "") + "/chat/completions",
                            mapper.writeValueAsString(body), Duration.ofSeconds(timeoutSeconds),
                            rateLimit(profile))
                    .header("Content-Type", "application/json")
                    .header("Authorization", key == null ? null : "Bearer " + key);

            LOG.fine(() -> "LLM request: " + messages.size() + " message(s) -> " + callSummary(profile, key != null));
            JsonNode content = http.json(call)
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("LLM API returned no choices from " + profile.baseUrl());
            }
            return content.asText();
        } catch (RateLimitedException e) {
            throw e;
        } catch (OutboundHttpException e) {
            throw new IllegalStateException("LLM API " + e.status() + ": " + e.bodySnippet() + " ["
                    + callSummary(profile, key != null) + "]", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM request failed (" + profile.baseUrl() + ")", e);
        }
    }

    /** Charged to the connection, so two accounts on two vendors never share a window. */
    RateLimit rateLimit(LlmProfile profile) {
        return policies.forConnection(profile.connectionId(), LlmConnections.TYPE, profile.rateLimit(),
                profile.rateLimitMode().orElse(RateLimitMode.FAIL_FAST));
    }

    /** Never includes the key, only whether one is set. */
    String callSummary(LlmProfile profile, boolean hasKey) {
        return "profile=" + profile.name() + ", connection=" + profile.connectionId()
                + ", base-url=" + profile.baseUrl() + ", model=" + profile.model()
                + ", temperature=" + profile.temperature().map(String::valueOf).orElse("<vendor default>")
                + ", max-tokens=" + profile.maxTokens().map(String::valueOf).orElse("<vendor default>")
                + ", api-key=" + (hasKey ? "present" : "ABSENT -> sending no Authorization header");
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode node = messages.addObject();
        node.put("role", role);
        node.put("content", content);
    }
}
