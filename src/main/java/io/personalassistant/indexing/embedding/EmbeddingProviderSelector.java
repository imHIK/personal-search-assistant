package io.personalassistant.indexing.embedding;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The produced bean is not {@code @ProviderImpl}, so the lookup never selects itself. */
@ApplicationScoped
public class EmbeddingProviderSelector {

    private static final Logger LOG = Logger.getLogger(EmbeddingProviderSelector.class.getName());

    @Produces
    @ApplicationScoped
    public EmbeddingProvider active(@ProviderImpl Instance<EmbeddingProvider> implementations,
                                    @ConfigProperty(name = "app.embedding.provider",
                                            defaultValue = "openai-embed") String selected) {
        List<String> available = new ArrayList<>();
        for (EmbeddingProvider provider : implementations) {
            available.add(provider.providerId());
            if (provider.providerId().equals(selected)) {
                LOG.info("Active embedding provider: " + selected + " (model=" + provider.model()
                        + ", dim=" + provider.dimension() + ")");
                return provider;
            }
        }
        throw new IllegalStateException("No EmbeddingProvider with providerId '" + selected
                + "'. Set app.embedding.provider to one of: " + available);
    }
}
