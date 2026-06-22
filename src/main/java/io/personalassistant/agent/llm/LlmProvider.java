package io.personalassistant.agent.llm;

import java.util.List;

/**
 * Port over a chat/completion LLM. Implementations may target a hosted REST endpoint or a
 * local model (e.g. Ollama). Kept minimal now; streaming/tool-calling can be added without
 * breaking callers.
 */
public interface LlmProvider {

    String model();

    /**
     * @param system   system / instruction prompt
     * @param messages ordered conversation turns
     * @return the model's reply
     */
    String complete(String system, List<Message> messages);

    /** @param role "user" or "assistant" */
    record Message(String role, String content) {}
}
