package io.personalassistant.agent.llm;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the single active {@link LlmProvider} — the {@code @Default} bean {@code DefaultSearchAgent}
 * injects — by matching {@code app.llm.provider} against the {@code providerId()} of each
 * {@link ProviderImpl}-qualified implementation discovered via CDI. Mirror of
 * {@code EmbeddingProviderSelector}; adding a new LLM backend is a new {@code @ProviderImpl} bean plus
 * a config value.
 */
@ApplicationScoped
public class LlmProviderSelector {

    private static final Logger LOG = Logger.getLogger(LlmProviderSelector.class.getName());

    @Produces
    @ApplicationScoped
    public LlmProvider active(@ProviderImpl Instance<LlmProvider> implementations,
                              @ConfigProperty(name = "app.llm.provider",
                                      defaultValue = "openai-compat") String selected) {
        List<String> available = new ArrayList<>();
        for (LlmProvider provider : implementations) {
            available.add(provider.providerId());
            if (provider.providerId().equals(selected)) {
                LOG.info("Active LLM provider: " + selected + " (model=" + provider.model() + ")");
                return provider;
            }
        }
        throw new IllegalStateException("No LlmProvider with providerId '" + selected
                + "'. Set app.llm.provider to one of: " + available);
    }
}
