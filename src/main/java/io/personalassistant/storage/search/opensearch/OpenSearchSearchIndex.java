package io.personalassistant.storage.search.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;

/**
 * OpenSearch adapter for {@link SearchIndex}. Chunks are written with their {@code chunkId} as
 * the document id (so re-indexing overwrites idempotently), retrieved via BM25 {@code multi_match}
 * and {@code knn} over the embedding, and removed by {@code _delete_by_query} mirroring Mongo
 * cascades. The app always targets the {@code chunks} alias.
 */
@ApplicationScoped
public class OpenSearchSearchIndex implements SearchIndex {

    private static final Logger LOG = Logger.getLogger(OpenSearchSearchIndex.class.getName());
    private static final int SNIPPET_CHARS = 280;

    private final RestClient client;
    private final String alias;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public OpenSearchSearchIndex(RestClient client,
                                 @ConfigProperty(name = "opensearch.index.chunks",
                                         defaultValue = "chunks") String alias) {
        this.client = client;
        this.alias = alias;
    }

    @Override
    public void indexChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (Chunk chunk : chunks) {
            ObjectNode action = mapper.createObjectNode();
            action.putObject("index").put("_index", alias).put("_id", chunk.id());
            ndjson.append(write(action)).append('\n');
            ndjson.append(write(toDoc(chunk))).append('\n');
        }
        Request request = new Request("POST", "/_bulk");
        request.setEntity(new StringEntity(ndjson.toString(),
                ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
        JsonNode response = execute(request);
        if (response != null && response.path("errors").asBoolean(false)) {
            LOG.warning("Bulk indexing reported item errors: " + response.path("items"));
        }
    }

    @Override
    public List<SearchHit> lexicalSearch(SearchQuery query, int limit) {
        ObjectNode must = mapper.createObjectNode();
        ObjectNode multiMatch = must.putObject("multi_match");
        multiMatch.put("query", query.text() == null ? "" : query.text());
        multiMatch.putArray("fields").add("text").add("title");
        return search(query, limit, must);
    }

    @Override
    public List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit) {
        if (vector == null) {
            return List.of();
        }
        ObjectNode must = mapper.createObjectNode();
        ObjectNode embedding = must.putObject("knn").putObject("embedding");
        ArrayNode vec = embedding.putArray("vector");
        for (float v : vector) {
            vec.add(v);
        }
        embedding.put("k", limit);
        return search(query, limit, must);
    }

    @Override
    public void deleteByEntity(String entityId) {
        deleteByTerm("entityId", entityId);
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        deleteByTerm("knowledgeId", knowledgeId);
    }

    @Override
    public void deleteByIterable(String knowledgeId, String iterableId) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode must = body.putObject("query").putObject("bool").putArray("must");
        must.addObject().putObject("term").put("knowledgeId", knowledgeId);
        must.addObject().putObject("term").put("iterableId", iterableId);
        Request request = new Request("POST", "/" + alias + "/_delete_by_query");
        request.addParameter("conflicts", "proceed");
        request.setJsonEntity(write(body));
        execute(request);
    }

    // ---- internals ---------------------------------------------------------------------------

    private List<SearchHit> search(SearchQuery query, int limit, ObjectNode mustClause) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", limit);
        ObjectNode bool = body.putObject("query").putObject("bool");
        bool.putArray("must").add(mustClause);
        bool.set("filter", filters(query));

        Request request = new Request("POST", "/" + alias + "/_search");
        request.setJsonEntity(write(body));
        JsonNode response = execute(request);
        return response == null ? List.of() : parseHits(response);
    }

    // Package-private for unit testing of field resolution.
    ArrayNode filters(SearchQuery query) {
        ArrayNode filters = mapper.createArrayNode();
        if (query.knowledgeIds() != null && !query.knowledgeIds().isEmpty()) {
            ObjectNode terms = mapper.createObjectNode();
            ArrayNode ids = terms.putObject("terms").putArray("knowledgeId");
            query.knowledgeIds().forEach(ids::add);
            filters.add(terms);
        }
        if (query.filters() != null) {
            // The filter key is used verbatim as the target field, so callers may filter on any
            // indexed field (top-level keyword fields like "sourceType"/"uri" or nested
            // "metadata.<key>"), rather than being locked to the metadata subtree.
            query.filters().forEach((field, value) -> {
                ObjectNode term = mapper.createObjectNode();
                term.putObject("term").put(field, String.valueOf(value));
                filters.add(term);
            });
        }
        return filters;
    }

    private List<SearchHit> parseHits(JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode src = hit.path("_source");
            String text = src.path("text").asText("");
            hits.add(new SearchHit(
                    src.path("chunkId").asText(null),
                    src.path("entityId").asText(null),
                    src.path("knowledgeId").asText(null),
                    src.path("title").asText(null),
                    snippet(text),
                    src.path("uri").asText(null),
                    hit.path("_score").asDouble(0.0),
                    toMap(src.path("metadata"))));
        }
        return hits;
    }

    private void deleteByTerm(String field, String value) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("query").putObject("term").put(field, value);
        Request request = new Request("POST", "/" + alias + "/_delete_by_query");
        request.addParameter("conflicts", "proceed");
        request.setJsonEntity(write(body));
        execute(request);
    }

    private ObjectNode toDoc(Chunk chunk) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("chunkId", chunk.id());
        doc.put("entityId", chunk.entityId());
        doc.put("knowledgeId", chunk.knowledgeId());
        doc.put("iterableId", chunk.iterableId());
        doc.put("sourceType", chunk.sourceType() == null ? null : chunk.sourceType().name());
        doc.put("text", chunk.text());
        doc.put("title", chunk.title());
        doc.put("uri", chunk.uri());
        doc.put("ordinal", chunk.ordinal());
        if (chunk.embedding() != null && chunk.embedding().vector() != null) {
            ArrayNode vec = doc.putArray("embedding");
            for (float v : chunk.embedding().vector()) {
                vec.add(v);
            }
        }
        doc.set("metadata", mapper.valueToTree(chunk.metadata() == null ? Map.of() : chunk.metadata()));
        doc.put("indexedAt", Instant.now().toString());
        return doc;
    }

    private JsonNode execute(Request request) {
        try {
            Response response = client.performRequest(request);
            String body = EntityUtils.toString(response.getEntity());
            return body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "OpenSearch request failed: " + request.getMethod() + " "
                    + request.getEndpoint(), e);
            throw new UncheckedIOException("OpenSearch request failed", e);
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON", e);
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static String snippet(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    }
}
