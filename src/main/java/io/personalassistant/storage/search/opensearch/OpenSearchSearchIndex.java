package io.personalassistant.storage.search.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Task;
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
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;

@ApplicationScoped
public class OpenSearchSearchIndex implements SearchIndex {

    private static final Logger LOG = Logger.getLogger(OpenSearchSearchIndex.class.getName());
    private static final int BULK_ERRORS_REPORTED = 3;
    private static final String[] SOURCE_EXCLUDES = {"embedding"};

    private final RestClient client;
    private final String alias;
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code <= 0} disables truncation, which is what an un-injected instance in a unit test gets. */
    @ConfigProperty(name = "app.search.snippet-chars", defaultValue = "280")
    int snippetChars;

    /** 0 disables highlighting. */
    @ConfigProperty(name = "app.search.highlight-fragments", defaultValue = "2")
    int highlightFragments;

    /** Optional {@code ^boost} suffixes; empty falls back to {@code text,title}. */
    @ConfigProperty(name = "app.search.lexical.fields", defaultValue = "text,title^2")
    List<String> lexicalFields;

    @ConfigProperty(name = "app.search.lexical.type", defaultValue = "best_fields")
    String lexicalType;

    /** Blank sends nothing, restoring OR-any-term matching. */
    @ConfigProperty(name = "app.search.lexical.minimum-should-match", defaultValue = "2<70%")
    String minimumShouldMatch;

    /** 0 omits the clause. */
    @ConfigProperty(name = "app.search.lexical.phrase-boost", defaultValue = "2.0")
    double phraseBoost;

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
            // Rejected items fail the whole call: success would record chunks OpenSearch never accepted.
            // Throwing routes the entity through retry, which re-runs the idempotent replace.
            LOG.warning("Bulk indexing reported item errors: " + response.path("items"));
            throw new IllegalStateException(bulkFailureSummary(response, chunks.size()));
        }
    }

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

    /** Filters run inside the query, so post-filtering is not a concern here. */
    ObjectNode lexicalBody(SearchQuery query, int limit) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", limit);
        excludeSource(body);
        ObjectNode bool = body.putObject("query").putObject("bool");
        String text = query.text() == null ? "" : query.text();

        // A bare multi_match scores any matching term, so minimum_should_match requires a real share of the
        // query to be present.
        ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
        multiMatch.put("query", text);
        ArrayNode fields = multiMatch.putArray("fields");
        for (String field : lexicalFields == null ? List.<String>of() : lexicalFields) {
            if (field != null && !field.isBlank()) {
                fields.add(field.trim());
            }
        }
        if (fields.isEmpty()) {
            fields.add("text").add("title");
        }
        if (lexicalType != null && !lexicalType.isBlank()) {
            multiMatch.put("type", lexicalType);
        }
        if (minimumShouldMatch != null && !minimumShouldMatch.isBlank()) {
            multiMatch.put("minimum_should_match", minimumShouldMatch);
        }

        // A phrase match is an optional boost, not a requirement.
        if (phraseBoost > 0) {
            ObjectNode phrase = bool.putArray("should").addObject().putObject("match_phrase")
                    .putObject("text");
            phrase.put("query", text);
            phrase.put("boost", phraseBoost);
        }

        bool.set("filter", filters(query));
        highlight(body);
        return body;
    }

    /**
     * The filters sit inside the knn clause: outside, they would apply after the global top k was chosen, and
     * a knowledge-scoped search could return nothing. Inside, HNSW honours them during traversal (lucene
     * engine).
     */
    ObjectNode vectorBody(SearchQuery query, float[] vector, int limit) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", limit);
        excludeSource(body);
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

    @Override
    public void ensureMetadataFields(Map<String, Task.FieldType> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        ObjectNode properties = mapper.createObjectNode();
        fields.forEach((name, type) -> properties.putObject(name).put("type", switch (type) {
            case NUMBER -> "double";
            case BOOLEAN -> "boolean";
            // A list is a multi-valued keyword: OpenSearch has no array type, any field takes many values.
            case TEXT, LIST -> "keyword";
        }));
        ObjectNode body = mapper.createObjectNode();
        body.putObject("properties").putObject("metadata").put("type", "object").set("properties", properties);
        Request request = new Request("PUT", "/" + alias + "/_mapping");
        request.setJsonEntity(write(body));
        try {
            client.performRequest(request);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 400) {
                throw new IllegalArgumentException("A field is already indexed with a different type: "
                        + reason(e), e);
            }
            throw new UncheckedIOException("OpenSearch refused the metadata mapping", e);
        } catch (IOException e) {
            throw new UncheckedIOException("OpenSearch request failed", e);
        }
    }

    private String reason(ResponseException e) {
        try {
            JsonNode error = mapper.readTree(EntityUtils.toString(e.getResponse().getEntity())).path("error");
            return error.path("reason").asText(e.getMessage());
        } catch (IOException | RuntimeException unreadable) {
            return e.getMessage();
        }
    }

    private static final List<String> RANGE_OPS = List.of("gte", "gt", "lte", "lt");

    private List<SearchHit> runSearch(ObjectNode body) {
        Request request = new Request("POST", "/" + alias + "/_search");
        request.setJsonEntity(write(body));
        JsonNode response = execute(request);
        return response == null ? List.of() : parseHits(response);
    }

    ArrayNode filters(SearchQuery query) {
        ArrayNode filters = mapper.createArrayNode();
        if (query.knowledgeIds() != null && !query.knowledgeIds().isEmpty()) {
            ObjectNode terms = mapper.createObjectNode();
            ArrayNode ids = terms.putObject("terms").putArray("knowledgeId");
            query.knowledgeIds().forEach(ids::add);
            filters.add(terms);
        }
        if (query.filters() != null) {
            // The key is the target field verbatim, so any indexed field can be filtered, not just metadata.
            query.filters().forEach((field, value) -> filters.add(clause(field, value)));
        }
        return filters;
    }

    /**
     * A map carrying gte/gt/lte/lt becomes a range; any other key in it is ignored, since forwarding it would
     * let a caller inject query DSL.
     */
    private ObjectNode clause(String field, Object value) {
        ObjectNode node = mapper.createObjectNode();
        if (value instanceof Map<?, ?> bounds) {
            ObjectNode range = node.putObject("range").putObject(field);
            boolean any = false;
            for (String op : RANGE_OPS) {
                Object bound = bounds.get(op);
                if (bound != null) {
                    putScalar(range, op, bound);
                    any = true;
                }
            }
            if (any) {
                return node;
            }
            // A map with no recognised bound becomes a term that visibly matches nothing, rather than
            // widening the results.
            node.removeAll();
        }
        putScalar(node.putObject("term"), field, value);
        return node;
    }

    /**
     * Keeps the JSON type: a string "true" against a boolean field matches nothing, and a quoted number
     * compares lexicographically.
     */
    private static void putScalar(ObjectNode target, String field, Object value) {
        switch (value) {
            case null -> target.putNull(field);
            case Boolean b -> target.put(field, b);
            case Integer i -> target.put(field, i);
            case Long l -> target.put(field, l);
            case Double d -> target.put(field, d);
            case Float f -> target.put(field, f);
            case Number n -> target.put(field, n.doubleValue());
            case Instant i -> target.put(field, i.toString());
            default -> target.put(field, String.valueOf(value));
        }
    }

    /** The embedding would otherwise ship back on every hit: orders of magnitude more bytes than the text. */
    private void excludeSource(ObjectNode body) {
        ArrayNode excludes = body.putObject("_source").putArray("excludes");
        for (String field : SOURCE_EXCLUDES) {
            excludes.add(field);
        }
    }

    /**
     * Lexical only: knn has no terms to highlight. The tags are stripped in {@link #fragment}, so the console
     * never renders markup.
     */
    private void highlight(ObjectNode body) {
        if (highlightFragments <= 0) {
            return;
        }
        ObjectNode fields = body.putObject("highlight")
                .put("fragment_size", Math.max(snippetChars, 1))
                .put("number_of_fragments", highlightFragments)
                .putObject("fields");
        fields.putObject("text");
        fields.putObject("title");
    }

    private List<SearchHit> parseHits(JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode src = hit.path("_source");
            String text = src.path("text").asText("");
            String highlighted = fragment(hit.path("highlight").path("text"));
            hits.add(new SearchHit(
                    src.path("chunkId").asText(null),
                    src.path("entityId").asText(null),
                    src.path("knowledgeId").asText(null),
                    src.path("ordinal").asInt(0),
                    src.path("title").asText(null),
                    text,
                    highlighted != null ? highlighted : snippet(text),
                    src.path("uri").asText(null),
                    hit.path("_score").asDouble(0.0),
                    toMap(src.path("metadata"))));
        }
        return hits;
    }

    /**
     * Null when the field produced none, so the caller falls back to the head of the text (the vector leg
     * always does).
     */
    private String fragment(JsonNode fragments) {
        if (!fragments.isArray() || fragments.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode f : fragments) {
            if (!out.isEmpty()) {
                out.append(" … ");
            }
            out.append(f.asText("").replace("<em>", "").replace("</em>", ""));
        }
        String joined = out.toString().trim();
        return joined.isEmpty() ? null : joined;
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
        doc.put("tokenCount", chunk.tokenCount());
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

    /** Metadata may hold Instant or Date values, and this plain ObjectMapper has no java.time module. */
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

    /** A non-positive snippetChars means no truncation. */
    private String snippet(String text) {
        if (text == null || snippetChars <= 0 || text.length() <= snippetChars) {
            return text;
        }
        return text.substring(0, snippetChars) + "…";
    }
}
