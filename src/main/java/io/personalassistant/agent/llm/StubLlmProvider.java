package io.personalassistant.agent.llm;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Placeholder LLM provider so the bean graph is complete. Replace with an Ollama (local)
 * or hosted REST implementation.
 */
@ApplicationScoped
public class StubLlmProvider implements LlmProvider {

    @Override
    public String model() {
        return "none";
    }

    @Override
    public String complete(String system, List<Message> messages) {
        throw new UnsupportedOperationException(
                "No LLM wired yet — implement LlmProvider (Ollama or hosted REST endpoint).");
    }
}
