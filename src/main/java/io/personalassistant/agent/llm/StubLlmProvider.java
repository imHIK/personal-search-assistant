package io.personalassistant.agent.llm;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** The app.llm.provider=none fallback. complete() throws on purpose, so a misconfiguration is loud. */
@ApplicationScoped
@ProviderImpl
public class StubLlmProvider implements LlmProvider {

    @Override
    public String providerId() {
        return "none";
    }

    @Override
    public String complete(LlmProfile profile, ResponseFormat format, String system, List<Message> messages) {
        throw new UnsupportedOperationException("LLM calls are off: app.llm.provider is none");
    }
}
