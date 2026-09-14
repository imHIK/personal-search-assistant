package io.personalassistant.agent.llm;

import java.util.List;

/**
 * Port over a chat/completion LLM. Implementations may target a hosted REST endpoint or a
 * local model (e.g. Ollama). Kept minimal now; streaming/tool-calling can be added without
 * breaking callers.
 */
public interface LlmProvider {

    /**
     * Stable id used to select this provider at runtime via {@code app.llm.provider}
     * (e.g. {@code "openai-compat"}, {@code "none"}). Distinct from {@link #model()}, which is
     * the specific model name sent to the endpoint.
     */
    String providerId();

    String model();

    /**
     * @param system   system / instruction prompt
     * @param messages ordered conversation turns
     * @return the model's reply
     */
    String complete(String system, List<Message> messages);

    /**
     * Same call, run under a named {@link LlmProfile} so a caller can ask for the model that suits its
     * role — a strong one for answering, a cheap one for bulk index-time work — without knowing how the
     * provider is configured.
     *
     * <p>A default method on purpose: a provider that has nothing to vary (the stub) or cannot vary it
     * needs no change, and the profile is a request rather than a requirement. Implementations that
     * honour it apply only the fields the profile actually sets, leaving the rest at their own defaults.
     */
    default String complete(LlmProfile profile, String system, List<Message> messages) {
        return complete(system, messages);
    }

    /**
     * Same call, asking the endpoint to constrain its reply shape.
     *
     * <p>A default method for the same reason as the profile overload: a provider that cannot constrain
     * output needs no change, and the format is a request rather than a requirement. Callers must
     * therefore treat {@link ResponseFormat#JSON_OBJECT} as a strong hint and still parse defensively —
     * a model can return fenced or prose-wrapped JSON even when asked not to, which is what
     * {@code JsonReplies} exists to absorb.
     */
    default String complete(LlmProfile profile, ResponseFormat format, String system,
                            List<Message> messages) {
        return complete(profile, system, messages);
    }

    /**
     * The reply shape asked of the endpoint. {@code TEXT} is the default and sends nothing, preserving
     * the request every existing caller already made.
     */
    enum ResponseFormat { TEXT, JSON_OBJECT }

    /** @param role "user" or "assistant" */
    record Message(String role, String content) {}
}
