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
    /** Cap on how many per-item bulk failures are named in the summary; the rest are counted. */
    private static final int BULK_ERRORS_REPORTED = 3;

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
            // Rejected items must fail the whole call. Logging and returning success let the caller
            // record chunkCount = chunks.size() when OpenSearch had accepted fewer — the entity then
            // looks fully indexed while part of it is missing. Throwing routes the entity through
            // the normal retry/backoff path, which re-runs the idempotent replace.
            LOG.warning("Bulk indexing reported item errors: " + response.path("items"));
            throw new IllegalStateException(bulkFailureSummary(response, chunks.size()));
        }
    }

    /**
     * Compact, actionable summary of a partially-failed bulk: how many items failed and why, naming
     * the first few document ids. The full {@code items} array goes to the log; this is what ends up
     * on the entity's {@code index.error} and in front of a user.
     */
    // Package-private so the summary can be tested against a hand-built response without a client.
    String bulkFailureSummary(JsonNode response, int total) {
        List<String> reasons = new ArrayList<>();
        int failed = 0;
        for (JsonNode item : response.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                failed++;
                if (reasons.size() < BULK_ERRORS_REPORTED) {
                    reasons.add(item.path("index").path("_id").asText("?") + " ("
                            + error.path("type").asText("unknown") + ": "
                            + error.path("reason").asText("no reason given") + ")");
                }
            }
        }
        String detail = String.join(", ", reasons);
        if (failed > reasons.size()) {
            detail += ", and " + (failed - reasons.size()) + " more";
        }
        return failed + " of " + total + " chunks rejected by OpenSearch: " + detail;
    }

    @Override
    public List<SearchHit> lexicalSearch(SearchQuery query, int limit) {
        return runSearch(lexicalBody(query, limit));
    }

    @Override
    public List<SearchHit> vectorSearch(SearchQuery query, float[] vector, int limit) {
        if (vector == null) {
            return List.of();
        }
        return runSearch(vectorBody(query, vector, limit));
    }

    /**
     * BM25 over {@code text}/{@code title}, scoped by {@code bool.filter}. Filters are applied while
     * the query runs here, so post-filtering is not a concern on this path.
     */
    // Package-private for query-shape tests.
    ObjectNode lexicalBody(SearchQuery query, int limit) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", limit);
        ObjectNode bool = body.putObject("query").putObject("bool");
        ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
        multiMatch.put("query", query.text() == null ? "" : query.text());
        multiMatch.putArray("fields").add("text").add("title");
        bool.set("filter", filters(query));
        return body;
    }

    /**
     * kNN over {@code embedding}, with the scoping clauses nested <em>inside</em> the knn query
     * rather than in the surrounding {@code bool.filter}.
     *
     * <p>This placement is the whole point. A filter sitting outside the knn clause is applied after
     * the k nearest neighbours have been chosen, so searching within one knowledge in a large corpus
     * legitimately returns few or no hits — the global top-k simply belongs to other knowledges.
     * Nested, the filter is honoured during HNSW graph traversal (with an automatic exact-search
     * fallback when the filtered set is small), so k counts matching documents. This requires the
     * lucene engine, which {@code OpenSearchIndexInitializer} already pins — no mapping change and no
     * re-index is needed.
     */
    // Package-private for query-shape tests.
    ObjectNode vectorBody(SearchQuery query, float[] vector, int limit) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", limit);
        ObjectNode embedding = body.putObject("query").putObject("bool").putArray("must")
                .addObject().putObject("knn").putObject("embedding");
        ArrayNode vec = embedding.putArray("vector");
        for (float v : vector) {
            vec.add(v);
        }
        embedding.put("k", limit);
        ArrayNode clauses = filters(query);
        if (!clauses.isEmpty()) {
            embedding.putObject("filter").putObject("bool").set("filter", clauses);
        }
        return body;
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

    private List<SearchHit> runSearch(ObjectNode body) {
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
        doc.set("metadata", mapper.valueToTree(jsonSafe(chunk.metadata() == null ? Map.of() : chunk.metadata())));
        doc.put("indexedAt", Instant.now().toString());
        return doc;
    }

    /**
     * Convert metadata values into JSON-friendly forms before serialization. The chunk's metadata
     * is carried verbatim from the entity, so it may contain {@link Instant}/{@link java.util.Date}
     * facets (e.g. {@code modifiedAt}) — and this adapter's plain {@link ObjectMapper} has no
     * java.time module, so it would otherwise throw. Temporals are emitted as ISO-8601 strings;
     * maps/lists are converted recursively; everything else passes through.
     */
    private Object jsonSafe(Object value) {
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), jsonSafe(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(jsonSafe(o));
            }
            return out;
        }
        return value;
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
