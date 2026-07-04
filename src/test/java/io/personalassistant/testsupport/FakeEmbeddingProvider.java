package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Embedding;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;

/** Fixed-dimension fake embeddings for indexing tests (no real model). */
public class FakeEmbeddingProvider implements EmbeddingProvider {

    private final int dim;

    public FakeEmbeddingProvider(int dim) {
        this.dim = dim;
    }

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public String model() {
        return "fake-" + dim;
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public Embedding embed(String text) {
        float[] v = new float[dim];
        if (text != null && !text.isEmpty()) {
            v[Math.floorMod(text.hashCode(), dim)] = 1.0f;
        }
        return new Embedding(model(), dim, v);
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        List<Embedding> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }
}
