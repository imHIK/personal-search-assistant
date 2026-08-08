package io.personalassistant.indexing.embedding;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.ProviderImpl;
import io.personalassistant.domain.model.Embedding;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Local, fully-offline semantic embeddings: runs a sentence-transformer (default
 * {@code bge-base-en-v1.5}, 768-dim) in-JVM through DJL on the ONNX Runtime engine. Private (no data
 * leaves the machine), unlimited, and CPU-friendly. Selected with {@code app.embedding.provider=onnx-bge}.
 *
 * <p>The model is <strong>loaded lazily</strong> on first use, not in the constructor, so the bean can
 * be instantiated (and its {@code providerId()} inspected by the selector) even when this provider is
 * not the active one and no model is present. DJL {@link Predictor}s are not thread-safe, so calls are
 * serialized on a single lazily-built predictor — adequate here since embedding is fast and CPU-bound;
 * a per-thread predictor pool is a later optimization.
 *
 * <p>Setup: point {@code app.embedding.onnx.model-path} at a directory containing the exported
 * {@code model.onnx}, {@code tokenizer.json} and {@code config.json}. BGE uses CLS pooling with L2
 * normalization (the defaults below); override via {@code app.embedding.onnx.pooling} /
 * {@code .normalize} for a different model. Keep {@code app.embedding.dimension} equal to the model's
 * output width and to the OpenSearch {@code knn_vector} mapping.
 */
@ApplicationScoped
@ProviderImpl
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = Logger.getLogger(OnnxEmbeddingProvider.class.getName());

    @ConfigProperty(name = "app.embedding.onnx.model", defaultValue = "bge-base-en-v1.5")
    String modelName;

    @ConfigProperty(name = "app.embedding.dimension", defaultValue = "768")
    int dimension;

    /** Optional: blank means no model exported yet, and embedding throws. See {@link ConfigText}. */
    @ConfigProperty(name = "app.embedding.onnx.model-path")
    Optional<String> modelPath;

    @ConfigProperty(name = "app.embedding.onnx.pooling", defaultValue = "cls")
    String pooling;

    @ConfigProperty(name = "app.embedding.onnx.normalize", defaultValue = "true")
    boolean normalize;

    private final Object lock = new Object();
    private volatile ZooModel<String, float[]> model;
    private volatile Predictor<String, float[]> predictor;

    @Override
    public String providerId() {
        return "onnx-bge";
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public Embedding embed(String text) {
        return embedAll(List.of(text == null ? "" : text)).get(0);
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        List<String> safe = new ArrayList<>(texts.size());
        for (String t : texts) {
            safe.add(t == null ? "" : t);
        }
        try {
            List<float[]> vectors;
            synchronized (lock) {
                vectors = predictor().batchPredict(safe);
            }
            List<Embedding> out = new ArrayList<>(vectors.size());
            for (float[] v : vectors) {
                out.add(new Embedding(modelName, dimension, v));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("ONNX embedding failed (model=" + modelName
                    + ", path=" + ConfigText.orNull(modelPath) + ")", e);
        }
    }

    /** Lazily loads the model + predictor on first use (double-checked under {@link #lock}). */
    private Predictor<String, float[]> predictor() throws Exception {
        Predictor<String, float[]> p = predictor;
        if (p != null) {
            return p;
        }
        synchronized (lock) {
            if (predictor == null) {
                String configured = ConfigText.orNull(modelPath);
                if (configured == null) {
                    throw new IllegalStateException(
                            "app.embedding.onnx.model-path is not set. Point it at a directory that "
                            + "contains the exported model.onnx, tokenizer.json and config.json.");
                }
                Path dir = Path.of(configured);
                if (!Files.isDirectory(dir)) {
                    throw new IllegalStateException("app.embedding.onnx.model-path is not a directory: " + dir);
                }
                LOG.info("Loading ONNX embedding model '" + modelName + "' from " + dir
                        + " (pooling=" + pooling + ", normalize=" + normalize + ")");
                Criteria<String, float[]> criteria = Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optModelPath(dir)
                        .optEngine("OnnxRuntime")
                        .optArgument("pooling", pooling)
                        .optArgument("normalize", normalize)
                        .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                        .build();
                model = criteria.loadModel();
                predictor = model.newPredictor();
                LOG.info("ONNX embedding model ready: " + modelName);
            }
            return predictor;
        }
    }

    @PreDestroy
    void close() {
        synchronized (lock) {
            if (predictor != null) {
                predictor.close();
            }
            if (model != null) {
                model.close();
            }
        }
    }
}
