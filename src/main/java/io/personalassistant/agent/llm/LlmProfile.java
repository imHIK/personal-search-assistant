package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import java.util.Optional;

/**
 * Per-call LLM overrides from {@code app.llm.profile.<name>.*}. Every field is optional; absent inherits the
 * provider default.
 *
 * @param maxTokens absent leaves it unsent, so the provider's default applies and a long answer can be cut
 *                  off silently
 * @param apiKey blank sends no auth header, which a local Ollama needs
 * @param rateLimitMode absent means fail fast
 */
public record LlmProfile(
        String name,
        Optional<String> baseUrl,
        Optional<String> model,
        Optional<Double> temperature,
        Optional<Integer> maxTokens,
        Optional<String> apiKey,
        Optional<RateLimitMode> rateLimitMode) {

    public static LlmProfile inherit(String name) {
        return new LlmProfile(name, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
