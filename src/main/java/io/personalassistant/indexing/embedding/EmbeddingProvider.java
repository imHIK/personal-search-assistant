package io.personalassistant.indexing.embedding;

import io.personalassistant.domain.model.Embedding;
import java.util.List;

/** The same provider must be used for indexing and querying. */
public interface EmbeddingProvider {

    /** Matched against {@code app.embedding.provider} to select this implementation. */
    String providerId();

    String model();

    /** Must match the index's knn_vector width. */
    int dimension();

    Embedding embed(String text);

    /**
     * Retrieval models are trained asymmetrically, so a query may be embedded differently from a document.
     * The default suits a symmetric provider.
     */
    default Embedding embedQuery(String text) {
        return embed(text);
    }

    /**
     * Must return exactly {@code texts.size()} non-null embeddings in input order, failing loudly otherwise:
     * a chunk indexed without a vector is invisible to semantic search forever.
     */
    List<Embedding> embedAll(List<String> texts);
}
