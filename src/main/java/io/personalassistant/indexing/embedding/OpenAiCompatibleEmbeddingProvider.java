package io.personalassistant.indexing.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.ProviderImpl;
import io.personalassistant.common.http.HttpCall;
import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.common.http.OutboundHttpException;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.domain.model.Embedding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Embeddings over the OpenAI-compatible {@code /embeddings} schema (Gemini, Jina, Ollama, …). A returned
 * width other than {@code app.embedding.dimension} throws rather than corrupting the index.
 */
@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleEmbeddingProvider.class.getName());

    private static final String DOCUMENT_TASK = "RETRIEVAL_DOCUMENT";
    private static final String QUERY_TASK = "RETRIEVAL_QUERY";

    @ConfigProperty(name = "app.embedding.openai.base-url",
            defaultValue = "https://generativelanguage.googleapis.com/v1beta/openai")
    String baseUrl;

    /**
     * Gemini's compatibility layer wants the full resource name ({@code models/gemini-embedding-001}); a bare
     * id returns 404, as does a retired model.
     */
    @ConfigProperty(name = "app.embedding.openai.model", defaultValue = "models/gemini-embedding-001")
    String modelName;

    /** Blank sends no Authorization header (a local Ollama). */
    @ConfigProperty(name = "app.embedding.openai.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "app.embedding.dimension")
    int dimension;

    /**
     * The width to ask for (Matryoshka models return a meaningful prefix); 0 omits it. Separate from
     * {@code app.embedding.dimension}, which is what the index requires, so a server that ignores the request
     * fails the width check instead of writing mis-sized vectors.
     */
    @ConfigProperty(name = "app.embedding.openai.dimensions", defaultValue = "768")
    int requestedDimensions;

    @ConfigProperty(name = "app.embedding.openai.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    /**
     * Off by default: the OpenAI-compatible Gemini endpoint rejects {@code task_type} with a 400, which would
     * fail every embedding.
     */
    @ConfigProperty(name = "app.embedding.openai.task-type-enabled", defaultValue = "false")
    boolean taskTypeEnabled;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean configLogged = new AtomicBoolean();
    private final OutboundHttp http;
    private final RateLimitPolicies policies;

    @Inject
    public OpenAiCompatibleEmbeddingProvider(OutboundHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public String providerId() {
        return "openai-embed";
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
    public Embedding embedQuery(String text) {
        return embed(List.of(text == null ? "" : text), QUERY_TASK, RateLimitMode.FAIL_FAST).get(0);
    }

    @Override
    public List<Embedding> embedAll(List<String> texts) {
        return embed(texts, DOCUMENT_TASK, RateLimitMode.WAIT);
    }

    /**
     * embedQuery is reached only from a search and embedAll only from indexing: that split is the rate-limit
     * boundary.
     */
    private List<Embedding> embed(List<String> texts, String taskType, RateLimitMode mode) {
        logConfigOnce();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
            if (requestedDimensions > 0) {
                body.put("dimensions", requestedDimensions);
            }
            if (taskTypeEnabled) {
                body.put("task_type", taskType);
            }
            ArrayNode input = body.putArray("input");
            for (String t : texts) {
                input.add(t == null ? "" : t);
            }

            RateLimit limit = policies.forEmbedding(providerId(), mode);
            HttpCall call = HttpCall
                    .post(baseUrl.replaceAll("/+$", "") + "/embeddings",
                            mapper.writeValueAsString(body), Duration.ofSeconds(timeoutSeconds), limit)
                    .header("Content-Type", "application/json")
                    .header("Authorization",
                            ConfigText.isSet(apiKey) ? "Bearer " + ConfigText.orNull(apiKey) : null);

            LOG.fine(() -> "Embedding request: " + texts.size() + " input(s) -> " + configSummary());
            JsonNode data = http.json(call).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("Embedding API returned " + data.size()
                        + " vectors for " + texts.size() + " inputs");
            }
            // Placed by reported index, then every slot is checked for null: colliding indices would
            // otherwise leave a hole.
            Embedding[] ordered = new Embedding[texts.size()];
            int position = 0;
            for (JsonNode item : data) {
                // Gemini omits index on the first item (proto3 drops default-valued fields), so
                // position is the fallback. An index present and out of range is still rejected.
                int idx = item.hasNonNull("index") ? item.path("index").asInt(-1) : position;
                position++;
                if (idx < 0 || idx >= ordered.length) {
                    throw new IllegalStateException("Embedding API reported index " + idx
                            + " for a request of " + texts.size() + " inputs");
                }
                if (ordered[idx] != null) {
                    throw new IllegalStateException("Embedding API reported index " + idx + " twice");
                }
                float[] vector = toVector(item.path("embedding"));
                if (vector.length != dimension) {
                    throw new IllegalStateException(widthMismatch(vector.length));
                }
                ordered[idx] = new Embedding(modelName, dimension, vector);
            }
            List<Embedding> out = new ArrayList<>(ordered.length);
            for (int i = 0; i < ordered.length; i++) {
                if (ordered[i] == null) {
                    throw new IllegalStateException("Embedding API returned no vector for input " + i);
                }
                out.add(ordered[i]);
            }
            return out;
        } catch (RateLimitedException e) {
            throw e;
        } catch (OutboundHttpException e) {
            throw new IllegalStateException("Embedding API " + e.status() + ": " + e.bodySnippet()
                    + " [" + configSummary() + "]", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding request failed (" + baseUrl + ")", e);
        }
    }

    /**
     * A blank api-key is legitimate (a local Ollama), so this line is what tells "no key reached the JVM"
     * apart from a model problem.
     */
    private void logConfigOnce() {
        if (configLogged.compareAndSet(false, true)) {
            LOG.info("Embedding provider ready: " + configSummary());
        }
    }

    /** Never logs the key itself, only whether one resolved. */
    private String configSummary() {
        return "base-url=" + baseUrl + ", model=" + modelName + ", index-dimension=" + dimension
                + ", requested-dimensions=" + (requestedDimensions > 0 ? requestedDimensions : "omitted")
                + ", api-key=" + (ConfigText.orNull(apiKey) == null
                        ? "ABSENT -> sending no Authorization header" : "present");
    }

    private String widthMismatch(int actual) {
        String cause = requestedDimensions > 0
                ? " even though dimensions=" + requestedDimensions + " was requested (the API ignored it)"
                : " and no app.embedding.openai.dimensions was requested";
        return "Embedding model " + modelName + " returned " + actual + "-wide vectors"
                + cause + ", but app.embedding.dimension (and the OpenSearch knn mapping) is "
                + dimension + ". Either set app.embedding.openai.dimensions=" + dimension
                + " if the model supports it, or re-map onto a new index at width " + actual
                + " and re-index — see docs/opensearch-index.md.";
    }

    private static float[] toVector(JsonNode array) {
        float[] v = new float[array.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) array.get(i).asDouble();
        }
        return v;
    }

}
