package io.personalassistant.indexing.embedding;

import io.personalassistant.common.ProviderImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the single active {@link EmbeddingProvider} — the {@code @Default} bean every caller
 * injects — by matching {@code app.embedding.provider} against the {@code providerId()} of each
 * {@link ProviderImpl}-qualified implementation discovered via CDI.
 *
 * <p>Because the concrete providers carry {@link ProviderImpl} (and therefore are <em>not</em>
 * {@code @Default}), they are never injected directly; adding a new embedding model is a new
 * {@code @ProviderImpl} bean plus a config value, with no change to {@code DefaultSearchService},
 * {@code IndexingRunner}, or a central switch. The produced bean is not itself {@code @ProviderImpl},
 * so the lookup below can never select itself.
 */
@ApplicationScoped
public class EmbeddingProviderSelector {

    private static final Logger LOG = Logger.getLogger(EmbeddingProviderSelector.class.getName());

    @Produces
    @ApplicationScoped
    public EmbeddingProvider active(@ProviderImpl Instance<EmbeddingProvider> implementations,
                                    @ConfigProperty(name = "app.embedding.provider",
                                            defaultValue = "onnx-bge") String selected) {
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
