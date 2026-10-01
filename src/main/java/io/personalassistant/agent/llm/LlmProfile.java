package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicy;
import java.util.Optional;

/**
 * One call's endpoint, model and limits, resolved from an LLM connection.
 *
 * @param name the profile a task asked for, kept so logs and errors can name it
 * @param temperature absent is left out of the request, so the vendor default applies
 * @param maxTokens absent is left out too, and a long reply can then be cut off silently
 * @param apiKey absent sends no Authorization header, which a local Ollama needs
 * @param rateLimitMode absent means fail fast
 */
public record LlmProfile(
        String name,
        String connectionId,
        String baseUrl,
        String model,
        Optional<Double> temperature,
        Optional<Integer> maxTokens,
        Optional<String> apiKey,
        RateLimitPolicy rateLimit,
        Optional<RateLimitMode> rateLimitMode) {

    /** Background callers wait for the window instead of failing. */
    public LlmProfile withDefaultMode(RateLimitMode mode) {
        if (rateLimitMode.isPresent()) {
            return this;
        }
        return new LlmProfile(name, connectionId, baseUrl, model, temperature, maxTokens, apiKey, rateLimit,
                Optional.of(mode));
    }
}
