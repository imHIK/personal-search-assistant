package io.personalassistant.testsupport;

import io.personalassistant.domain.model.Embedding;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;

/** Fixed-dimension fake embeddings for indexing tests (no real model). */
public class FakeEmbeddingProvider implements EmbeddingProvider {

    /** How a misbehaving provider breaks its {@code embedAll} contract. */
    public enum Defect {
        NONE,
        /** Returns the right count but with a null in the middle — what a duplicated API index does. */
        HOLE,
        /** Returns fewer vectors than texts. */
        SHORT
    }

    private final int dim;
    private Defect defect = Defect.NONE;

    public FakeEmbeddingProvider(int dim) {
        this.dim = dim;
    }

    /** Make this provider violate the {@code embedAll} contract, to prove callers notice. */
    public FakeEmbeddingProvider breaking(Defect howItBreaks) {
        this.defect = howItBreaks;
        return this;
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
        if (defect == Defect.HOLE && !out.isEmpty()) {
            out.set(out.size() - 1, null);
        }
        if (defect == Defect.SHORT && !out.isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
