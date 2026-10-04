package io.personalassistant.domain.model;

/** @param dim must match the knn_vector width baked into the index */
public record Embedding(String model, int dim, float[] vector) {
    public Embedding {
        if (vector != null && vector.length != dim) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != declared dim " + dim);
        }
    }
}
