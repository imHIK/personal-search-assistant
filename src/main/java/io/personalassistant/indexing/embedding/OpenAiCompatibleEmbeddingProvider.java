package io.personalassistant.indexing.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.common.ConfigText;
import io.personalassistant.common.ProviderImpl;
import io.personalassistant.domain.model.Embedding;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hosted embeddings over the OpenAI-compatible {@code POST {base-url}/embeddings} schema, which
 * Google Gemini, Jina, Mistral, Together, Ollama and others all speak. Adding another hosted model is
 * therefore config only (base-url + model + api-key), not code. Selected with
 * {@code app.embedding.provider=openai-embed}.
 *
 * <p>Defaults target Gemini's OpenAI-compatible endpoint with {@code models/gemini-embedding-001},
 * natively 3072-dim but requested at 768 via {@code app.embedding.openai.dimensions}. The returned
 * vector width must equal {@code app.embedding.dimension} and the OpenSearch {@code knn_vector}
 * mapping; a mismatch throws rather than corrupting the index. The API key is read from config so it
 * can be sourced from an env var, e.g. {@code app.embedding.openai.api-key=${GEMINI_API_KEY:}} —
 * note that an env var missing from the JVM's environment resolves to blank and is not an error
 * here, so the resolved state is logged and carried in failures (see {@code configSummary()}).
 */
@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleEmbeddingProvider.class.getName());

    @ConfigProperty(name = "app.embedding.openai.base-url",
            defaultValue = "https://generativelanguage.googleapis.com/v1beta/openai")
    String baseUrl;

    /**
     * Model id exactly as the endpoint expects it. Gemini's compatibility layer wants the full
     * resource name ({@code models/gemini-embedding-001}); a bare id returns 404 "Requested entity was
     * not found", which is also what a retired model returns — so check {@code GET {base-url}/models}
     * before assuming the model is gone. Other OpenAI-compatible servers take a bare id.
     */
    @ConfigProperty(name = "app.embedding.openai.model", defaultValue = "models/gemini-embedding-001")
    String modelName;

    /** Optional: blank means send no Authorization header (e.g. a local Ollama). See {@link ConfigText}. */
    @ConfigProperty(name = "app.embedding.openai.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "app.embedding.dimension", defaultValue = "768")
    int dimension;

    /**
     * Output width to ask the API for, sent as the OpenAI {@code dimensions} parameter; 0 omits it.
     * Matryoshka-trained models return a meaningful shorter prefix on request — {@code
     * gemini-embedding-001} is natively 3072 — which is what lets a 768-wide index keep working after
     * a model swap instead of needing a re-map.
     *
     * <p>Deliberately separate from {@code app.embedding.dimension}: that one states what the index
     * mapping <em>requires</em>, this one what we <em>ask</em> for. Deriving the request from the
     * index width would silently start truncating vectors the moment someone edited the mapping, and
     * a server that ignores the parameter (some OpenAI-compatible backends reject unknown fields
     * instead) must fail loudly rather than write mis-sized vectors — hence the explicit check below.
     */
    @ConfigProperty(name = "app.embedding.openai.dimensions", defaultValue = "0")
    int requestedDimensions;

    @ConfigProperty(name = "app.embedding.openai.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final AtomicBoolean configLogged = new AtomicBoolean();

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
    public List<Embedding> embedAll(List<String> texts) {
        logConfigOnce();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
            if (requestedDimensions > 0) {
                body.put("dimensions", requestedDimensions);
            }
            ArrayNode input = body.putArray("input");
            for (String t : texts) {
                input.add(t == null ? "" : t);
            }

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            String key = ConfigText.orNull(apiKey);
            if (key != null) {
                request.header("Authorization", "Bearer " + key);
            }

            LOG.fine(() -> "Embedding request: " + texts.size() + " input(s) -> " + configSummary());
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // The resolved config travels with the error on purpose: vendors report a missing key
                // as things that read like other faults (Gemini answers an unauthenticated call with
                // 404 "Requested entity was not found", i.e. exactly like a bad model id), so the
                // status alone sends you looking in the wrong place.
                throw new IllegalStateException("Embedding API " + response.statusCode() + ": "
                        + snippet(response.body()) + " [" + configSummary() + "]");
            }

            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("Embedding API returned " + data.size()
                        + " vectors for " + texts.size() + " inputs");
            }
            // Place each vector at its reported index so order matches the input regardless of API ordering.
            Embedding[] ordered = new Embedding[texts.size()];
            for (JsonNode item : data) {
                int idx = item.path("index").asInt();
                float[] vector = toVector(item.path("embedding"));
                if (vector.length != dimension) {
                    throw new IllegalStateException(widthMismatch(vector.length));
                }
                ordered[idx] = new Embedding(modelName, dimension, vector);
            }
            List<Embedding> out = new ArrayList<>(ordered.length);
            for (Embedding e : ordered) {
                out.add(e);
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding request failed (" + baseUrl + ")", e);
        }
    }

    /**
     * One INFO line, on first use, naming what this provider actually resolved to. A blank api-key is
     * not an error — a local Ollama needs none — so the request simply goes out unauthenticated and
     * the vendor decides what to call that; this line is what distinguishes "no key reached the JVM"
     * from a genuine model or endpoint problem. Logged lazily rather than at startup because the bean
     * is only initialised when the provider is actually selected.
     */
    private void logConfigOnce() {
        if (configLogged.compareAndSet(false, true)) {
            LOG.info("Embedding provider ready: " + configSummary());
        }
    }

    /** Never logs the key itself — only whether one resolved, which is the part that goes wrong. */
    private String configSummary() {
        return "base-url=" + baseUrl + ", model=" + modelName + ", index-dimension=" + dimension
                + ", requested-dimensions=" + (requestedDimensions > 0 ? requestedDimensions : "omitted")
                + ", api-key=" + (ConfigText.orNull(apiKey) == null
                        ? "ABSENT -> sending no Authorization header" : "present");
    }

    /**
     * Spells out the fix, because the two ways this fails are indistinguishable from the stack trace:
     * a model whose native width simply differs, or a server that accepted {@code dimensions} and
     * ignored it. Either way the vectors would be unusable against the knn mapping.
     */
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

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
