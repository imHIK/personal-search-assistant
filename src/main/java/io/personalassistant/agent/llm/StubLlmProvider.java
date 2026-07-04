package io.personalassistant.agent.llm;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Placeholder LLM provider that keeps the bean graph complete and serves as the safe
 * {@code app.llm.provider=none} fallback. Selecting a real provider (e.g. {@code openai-compat})
 * supersedes it. Its {@link #complete} intentionally throws so a misconfiguration surfaces loudly
 * rather than silently returning nothing.
 */
@ApplicationScoped
@ProviderImpl
public class StubLlmProvider implements LlmProvider {

    @Override
    public String providerId() {
        return "none";
    }

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
