package io.personalassistant.indexing.embedding;

import io.personalassistant.domain.model.Embedding;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Placeholder embedding provider so the bean graph is complete and the app boots. Reports
 * the configured model/dimension but cannot embed yet. Replace with a local ONNX
 * sentence-transformer or a hosted embedding API.
 */
@ApplicationScoped
public class StubEmbeddingProvider implements EmbeddingProvider {

    @ConfigProperty(name = "app.embedding.model", defaultValue = "all-MiniLM-L6-v2")
    String model;

    @ConfigProperty(name = "app.embedding.dimension", defaultValue = "384")
    int dimension;

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public Embedding embed(String text) {
        throw new UnsupportedOperationException(
                "No embedding model wired yet — implement EmbeddingProvider (local ONNX or hosted API).");
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        throw new UnsupportedOperationException(
                "No embedding model wired yet — implement EmbeddingProvider (local ONNX or hosted API).");
    }
}
