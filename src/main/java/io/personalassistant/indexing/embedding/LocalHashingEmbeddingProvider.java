package io.personalassistant.indexing.embedding;

import io.personalassistant.common.ProviderImpl;
import io.personalassistant.domain.model.Embedding;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A real, fully-offline embedding provider using the hashing-trick: each token is hashed to a
 * dimension and accumulated with a sign derived from a second hash, then the vector is
 * L2-normalized. It is deterministic and dependency-free, so indexing and querying stay
 * consistent and the hybrid pipeline is exercisable end-to-end without external services.
 *
 * <p>This is a baseline suitable for development, tests, and small corpora — <em>not</em> a
 * semantic model. Swap in an ONNX sentence-transformer or a hosted embedding API by providing a
 * different {@link EmbeddingProvider} bean; nothing else changes. Keep {@code app.embedding.*}
 * (model + dimension) pinned to whatever the OpenSearch {@code knn_vector} mapping expects.
 */
@ApplicationScoped
@ProviderImpl
public class LocalHashingEmbeddingProvider implements EmbeddingProvider {

    @ConfigProperty(name = "app.embedding.model", defaultValue = "local-hashing-v1")
    String model;

    @ConfigProperty(name = "app.embedding.dimension")
    int dimension;

    @Override
    public String providerId() {
        return "local-hashing";
    }

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
        float[] vector = new float[dimension];
        if (text != null) {
            for (String token : tokenize(text)) {
                int bucket = Math.floorMod(hash(token, 0x9E3779B1), dimension);
                int sign = (hash(token, 0x85EBCA77) & 1) == 0 ? 1 : -1;
                vector[bucket] += sign;
            }
        }
        normalize(vector);
        return new Embedding(model, dimension, vector);
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        List<Embedding> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : text.toLowerCase().split("[^\\p{Alnum}]+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static int hash(String token, int seed) {
        CRC32 crc = new CRC32();
        crc.update(seed);
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    private static void normalize(float[] vector) {
        double sumSq = 0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}
