package io.personalassistant.indexing.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalHashingEmbeddingProviderTest {

    private LocalHashingEmbeddingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalHashingEmbeddingProvider();
        provider.model = "local-hashing-v1";
        provider.dimension = 32;
    }

    @Test
    void producesDeterministicUnitVectors() {
        Embedding a = provider.embed("the quick brown fox");
        Embedding b = provider.embed("the quick brown fox");

        assertEquals(32, a.vector().length);
        assertArrayEquals(a.vector(), b.vector(), "embeddings must be deterministic");

        double norm = 0;
        for (float v : a.vector()) {
            norm += (double) v * v;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-5, "vector should be L2-normalized");
    }

    @Test
    void emptyTextYieldsZeroVector() {
        Embedding e = provider.embed("");
        for (float v : e.vector()) {
            assertEquals(0.0f, v);
        }
    }

    @Test
    void differentTextDiffersAndBatchMatchesSingle() {
        assertEquals(provider.dimension(), provider.embed("alpha").dim());
        var batch = provider.embedAll(java.util.List.of("alpha", "beta"));
        assertEquals(2, batch.size());
        assertArrayEquals(provider.embed("alpha").vector(), batch.get(0).vector());
        boolean different = !java.util.Arrays.equals(batch.get(0).vector(), batch.get(1).vector());
        assertTrue(different, "distinct inputs should generally produce distinct vectors");
    }
}
