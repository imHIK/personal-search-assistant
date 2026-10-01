package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicy;
import java.util.Optional;

/**
 * Per-call LLM overrides, from an LLM connection or {@code app.llm.profile.<name>.*}. Every field is optional;
 * absent inherits the provider default.
 *
 * @param maxTokens absent leaves it unsent, so the provider's default applies and a long answer can be cut
 *                  off silently
 * @param apiKey blank sends no auth header, which a local Ollama needs
 * @param rateLimitMode absent means fail fast
 * @param connectionId present when an LLM connection backs the profile; calls are then charged to that
 *                     connection's bucket instead of the shared provider one
 */
public record LlmProfile(
        String name,
        Optional<String> baseUrl,
        Optional<String> model,
        Optional<Double> temperature,
        Optional<Integer> maxTokens,
        Optional<String> apiKey,
        Optional<RateLimitMode> rateLimitMode,
        Optional<String> connectionId,
        Optional<RateLimitPolicy> rateLimit) {

    public LlmProfile(String name, Optional<String> baseUrl, Optional<String> model,
                      Optional<Double> temperature, Optional<Integer> maxTokens, Optional<String> apiKey,
                      Optional<RateLimitMode> rateLimitMode) {
        this(name, baseUrl, model, temperature, maxTokens, apiKey, rateLimitMode, Optional.empty(),
                Optional.empty());
    }

    public static LlmProfile inherit(String name) {
        return new LlmProfile(name, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Background callers wait for the window instead of failing, unless the profile says otherwise. */
    public LlmProfile withDefaultMode(RateLimitMode mode) {
        if (rateLimitMode.isPresent()) {
            return this;
        }
        return new LlmProfile(name, baseUrl, model, temperature, maxTokens, apiKey, Optional.of(mode),
                connectionId, rateLimit);
    }
}
