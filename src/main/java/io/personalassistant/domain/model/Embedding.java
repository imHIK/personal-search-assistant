package io.personalassistant.domain.model;

/**
 * A dense vector plus the identity of the model that produced it.
 * The model + dimension travel with the vector so that mismatches
 * (e.g. after a model swap) are detectable rather than silent.
 *
 * @param model  identifier of the embedding model, e.g. "all-MiniLM-L6-v2"
 * @param dim    vector length; must match the OpenSearch index mapping
 * @param vector the embedding values
 */
public record Embedding(String model, int dim, float[] vector) {
    public Embedding {
        if (vector != null && vector.length != dim) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != declared dim " + dim);
        }
    }
}
