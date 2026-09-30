package io.personalassistant.testsupport;

import io.personalassistant.common.ratelimit.RateLimitKey;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Embedding;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FakeEmbeddingProvider implements EmbeddingProvider {

    public enum Defect {
        NONE,
        HOLE,
        SHORT
    }

    /** Includes the calls {@link #embedAll} makes internally. */
    public int embedCalls;

    /** Refused calls included. */
    public int queryCalls;

    /**
     * When set, {@link #embedAll} and {@link #embedQuery} throw {@link RateLimitedException} with this
     * {@code retryAt}.
     */
    public Instant rateLimitedUntil;

    private final int dim;
    private Defect defect = Defect.NONE;

    public FakeEmbeddingProvider(int dim) {
        this.dim = dim;
    }

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
        embedCalls++;
        float[] v = new float[dim];
        if (text != null && !text.isEmpty()) {
            v[Math.floorMod(text.hashCode(), dim)] = 1.0f;
        }
        return new Embedding(model(), dim, v);
    }

    @Override
    public Embedding embedQuery(String text) {
        queryCalls++;
        if (rateLimitedUntil != null) {
            throw new RateLimitedException(RateLimitKey.embedding(providerId()), rateLimitedUntil);
        }
        return embed(text);
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        if (rateLimitedUntil != null) {
            throw new RateLimitedException(RateLimitKey.embedding(providerId()), rateLimitedUntil);
        }
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
