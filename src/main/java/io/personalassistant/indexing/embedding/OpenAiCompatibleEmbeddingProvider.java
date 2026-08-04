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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hosted embeddings over the OpenAI-compatible {@code POST {base-url}/embeddings} schema, which
 * Google Gemini, Jina, Mistral, Together, Ollama and others all speak. Adding another hosted model is
 * therefore config only (base-url + model + api-key), not code. Selected with
 * {@code app.embedding.provider=openai-embed}.
 *
 * <p>Defaults target Gemini's OpenAI-compatible endpoint with {@code text-embedding-004} (768-dim).
 * The returned vector width must equal {@code app.embedding.dimension} and the OpenSearch
 * {@code knn_vector} mapping; a mismatch throws (via {@link Embedding}) rather than corrupting the index.
 * The API key is read from config so it can be sourced from an env var, e.g.
 * {@code app.embedding.openai.api-key=${GEMINI_API_KEY:}}.
 */
@ApplicationScoped
@ProviderImpl
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    @ConfigProperty(name = "app.embedding.openai.base-url",
            defaultValue = "https://generativelanguage.googleapis.com/v1beta/openai")
    String baseUrl;

    @ConfigProperty(name = "app.embedding.openai.model", defaultValue = "text-embedding-004")
    String modelName;

    /** Optional: blank means send no Authorization header (e.g. a local Ollama). See {@link ConfigText}. */
    @ConfigProperty(name = "app.embedding.openai.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "app.embedding.dimension", defaultValue = "768")
    int dimension;

    @ConfigProperty(name = "app.embedding.openai.timeout-seconds", defaultValue = "60")
    long timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

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
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
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

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Embedding API " + response.statusCode() + ": "
                        + snippet(response.body()));
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
                ordered[idx] = new Embedding(modelName, dimension, toVector(item.path("embedding")));
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
