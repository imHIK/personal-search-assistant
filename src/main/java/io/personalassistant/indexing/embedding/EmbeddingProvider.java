package io.personalassistant.indexing.embedding;

import io.personalassistant.domain.model.Embedding;
import java.util.List;

/**
 * Produces vector embeddings. Implementations may call a hosted API or run a local model
 * (e.g. ONNX sentence-transformers) — swapping one for the other is a config change for
 * the caller. The same provider must be used for indexing and querying.
 */
public interface EmbeddingProvider {

    /**
     * Stable id used to select this provider at runtime via {@code app.embedding.provider}
     * (e.g. {@code "onnx-bge"}, {@code "openai-embed"}, {@code "local-hashing"}). Distinct from
     * {@link #model()}, which is the specific model label recorded with each vector.
     */
    String providerId();

    /** Model identifier, recorded alongside every vector for compatibility checks. */
    String model();

    /** Vector dimensionality; must match the OpenSearch {@code knn_vector} mapping. */
    int dimension();

    /** Embed a single text (typically a query). */
    Embedding embed(String text);

    /**
     * Embed many texts in one call (batched indexing).
     *
     * <p>Must return exactly {@code texts.size()} non-null embeddings, positionally aligned with the
     * input. Implementations must fail loudly rather than return a short or hole-y list: a chunk that
     * reaches the index without a vector is written without error and is then invisible to semantic
     * search forever. Callers enforce this, but the burden is the implementation's.
     */
    List<Embedding> embedAll(List<String> texts);
}
