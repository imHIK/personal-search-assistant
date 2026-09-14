package io.personalassistant.agent.llm;

import io.personalassistant.common.ratelimit.RateLimitMode;
import java.util.Optional;

/**
 * A named set of per-call LLM overrides — "which model, how hot, how long, and optionally on which
 * endpoint" — resolved from {@code app.llm.profile.<name>.*} by {@link LlmProfiles}.
 *
 * <p>Exists because LLM use is about to become frequent and heterogeneous: answering wants a strong
 * model, bulk index-time enrichment wants a cheap one, a listwise reranker wants a fast one, and they
 * may not even live on the same provider. Without a profile the only way to reach a second model is a
 * second bean with its own config keys, so every new feature would grow the wiring. With one, adding a
 * model for a new feature is a config edit and nothing more.
 *
 * <p><strong>Every field is optional and means "inherit the provider default" when absent.</strong> A
 * profile that only sets {@code model} keeps the provider's base-url, key, temperature and timeout;
 * one that sets {@code baseUrl} and {@code apiKey} moves that call to a different endpoint entirely.
 *
 * @param name        the profile name as configured, for logging and error messages
 * @param baseUrl     endpoint override — set this (with {@code apiKey}) to route one role elsewhere
 * @param model       model id override
 * @param temperature sampling temperature override
 * @param maxTokens   response length cap; absent leaves it unsent, which means the provider's own
 *                    default applies and a long answer can be cut off without any local signal
 * @param apiKey      credential override for {@code baseUrl}; blank/absent means send no auth header,
 *                    which is legitimate for a local Ollama
 * @param rateLimitMode what a rate-limited call should do. This belongs on the profile because it is a
 *                    property of the <em>role</em>, not of the provider: {@code answer} runs on a user's
 *                    request thread and should fail fast into {@code answerError}, while a digest role
 *                    runs on a scheduler and should wait. Absent means fail fast, which is the safe
 *                    default for the only caller that names no background profile — answering.
 */
public record LlmProfile(
        String name,
        Optional<String> baseUrl,
        Optional<String> model,
        Optional<Double> temperature,
        Optional<Integer> maxTokens,
        Optional<String> apiKey,
        Optional<RateLimitMode> rateLimitMode) {

    /** A profile that overrides nothing — every call falls back to the provider's own configuration. */
    public static LlmProfile inherit(String name) {
        return new LlmProfile(name, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
