package io.personalassistant.indexing.embedding;

import io.personalassistant.domain.model.Embedding;
import java.util.List;

/**
 * Produces vector embeddings. Implementations may call a hosted API or run a local model
 * (e.g. ONNX sentence-transformers) — swapping one for the other is a config change for
 * the caller. The same provider must be used for indexing and querying.
 */
public interface EmbeddingProvider {

    /** Model identifier, recorded alongside every vector for compatibility checks. */
    String model();

    /** Vector dimensionality; must match the OpenSearch {@code knn_vector} mapping. */
    int dimension();

    /** Embed a single text (typically a query). */
    Embedding embed(String text);

    /** Embed many texts in one call (batched indexing). Order matches the input. */
    List<Embedding> embedAll(List<String> texts);
}
