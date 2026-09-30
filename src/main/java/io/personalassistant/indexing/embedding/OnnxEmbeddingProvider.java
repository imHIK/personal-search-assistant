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
 * Local sentence-transformer embeddings through DJL on ONNX Runtime. Loaded lazily, so the bean can exist for
 * the selector without a model. Predictors are not thread-safe, so calls are serialized.
 * {@code app.embedding.onnx.model-path} must hold model.onnx, tokenizer.json and config.json.
 */
@ApplicationScoped
@ProviderImpl
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = Logger.getLogger(OnnxEmbeddingProvider.class.getName());

    @ConfigProperty(name = "app.embedding.onnx.model", defaultValue = "bge-base-en-v1.5")
    String modelName;

    @ConfigProperty(name = "app.embedding.dimension")
    int dimension;

    /** Blank means no model exported yet, and embedding throws. */
    @ConfigProperty(name = "app.embedding.onnx.model-path")
    Optional<String> modelPath;

    @ConfigProperty(name = "app.embedding.onnx.pooling", defaultValue = "cls")
    String pooling;

    @ConfigProperty(name = "app.embedding.onnx.normalize", defaultValue = "true")
    boolean normalize;

    /** Prepended to queries only. Model-specific: the wrong instruction is worse than none. */
    @ConfigProperty(name = "app.embedding.onnx.query-instruction")
    Optional<String> queryInstruction;

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

    /**
     * Joined with a space because spotless strips trailing whitespace from the property, which would glue the
     * instruction to the query.
     */
    @Override
    public Embedding embedQuery(String text) {
        String instruction = ConfigText.orNull(queryInstruction);
        String safe = text == null ? "" : text;
        return embedAll(List.of(instruction == null ? safe : instruction.strip() + " " + safe)).get(0);
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
