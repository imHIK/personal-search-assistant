package io.personalassistant.storage.search.opensearch;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;

/**
 * Creates the versioned physical index and its alias at startup; the app only talks to the alias. v3's change
 * is analysis (an English analyzer and a raw sub-field), which cannot change on a live index, hence a new
 * physical index. An existing v2 is left alone.
 */
@Singleton
public class OpenSearchIndexInitializer {

    private static final Logger LOG = Logger.getLogger(OpenSearchIndexInitializer.class.getName());
    private static final String PHYSICAL_INDEX = "chunks_v3_768";

    /**
     * Explicit types for the metadata fields filters compare on: dynamic inference is settled by the first
     * document indexed, and a string would make numeric comparisons lexicographic. Added on every boot, which
     * OpenSearch accepts in place for new sub-fields.
     */

    // TODO: why do we need job hunt specific details here
    private static final String METADATA_PROPERTIES = """
            {
              "company":     { "type": "keyword" },
              "location":    { "type": "keyword" },
              "board":       { "type": "keyword" },
              "seniority":   { "type": "keyword" },
              "dedupeKey":   { "type": "keyword" },
              "applyUrl":    { "type": "keyword" },
              "team":        { "type": "keyword" },
              "remote":      { "type": "boolean" },
              "sourceRank":  { "type": "integer" },
              "postedAt":    { "type": "date" }
            }""";

    private final RestClient client;
    private final String alias;
    private final int dimension;

    @Inject
    public OpenSearchIndexInitializer(RestClient client,
                                      @ConfigProperty(name = "opensearch.index.chunks",
                                              defaultValue = "chunks") String alias,
                                      // No defaultValue on purpose: this width is baked into the knn_vector
                                      // mapping, and a guessed one builds an index nothing fits. A missing
                                      // property must fail startup.
                                      @ConfigProperty(name = "app.embedding.dimension") int dimension) {
        this.client = client;
        this.alias = alias;
        this.dimension = dimension;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            client.performRequest(new Request("GET", "/" + PHYSICAL_INDEX));
            LOG.fine("OpenSearch index " + PHYSICAL_INDEX + " already exists");
        } catch (ResponseException notFound) {
            if (notFound.getResponse().getStatusLine().getStatusCode() == 404) {
                createIndex();
                return; // freshly created from the current mapping; nothing to add
            }
            LOG.log(Level.WARNING, "Unexpected response checking index existence", notFound);
            return;
        } catch (IOException e) {
            // OpenSearch may not be running here; log and continue.
            LOG.log(Level.WARNING, "Could not reach OpenSearch to ensure index; will retry on first use", e);
            return;
        }
        ensureMetadataMapping();
    }

    /**
     * Idempotent. A failure is logged, never fatal: metadata stays dynamically typed and only range filters
     * degrade.
     */
    private void ensureMetadataMapping() {
        Request request = new Request("PUT", "/" + PHYSICAL_INDEX + "/_mapping");
        request.setJsonEntity("{ \"properties\": { \"metadata\": { \"type\": \"object\", \"properties\": "
                + METADATA_PROPERTIES + " } } }");
        try {
            client.performRequest(request);
            LOG.fine("Ensured typed metadata sub-fields on " + PHYSICAL_INDEX);
        } catch (ResponseException e) {
            LOG.warning("Could not add typed metadata sub-fields to " + PHYSICAL_INDEX
                    + "; range filters on those fields may behave as text comparisons. A field already"
                    + " indexed with a conflicting type needs a new index — see docs/opensearch-index.md."
                    + " Response: " + e.getMessage());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not reach OpenSearch to update the metadata mapping", e);
        }
    }

    private void createIndex() {
        // Only claim the alias when nothing holds it: an alias over two indices fails writes and doubles
        // reads. On a version bump the operator swaps it once the re-index is verified.
        boolean aliasFree = !aliasExists();
        Request request = new Request("PUT", "/" + PHYSICAL_INDEX);
        request.setJsonEntity(mappingJson(aliasFree));
        try {
            client.performRequest(request);
            if (aliasFree) {
                LOG.info("Created OpenSearch index " + PHYSICAL_INDEX + " with alias " + alias);
            } else {
                LOG.warning("Created OpenSearch index " + PHYSICAL_INDEX + " WITHOUT the '" + alias
                        + "' alias, which an older index still holds. Nothing reads or writes the new"
                        + " index until you re-index into it and flip the alias — see"
                        + " docs/opensearch-index.md (Reindex flow).");
            }
        } catch (ResponseException already) {
            LOG.fine("Index creation race ignored: " + already.getMessage());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to create OpenSearch index", e);
        }
    }

    /** Treated as taken on any error: fail safe. */
    private boolean aliasExists() {
        try {
            client.performRequest(new Request("HEAD", "/_alias/" + alias));
            return true;
        } catch (ResponseException e) {
            return e.getResponse().getStatusLine().getStatusCode() != 404;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not check whether alias '" + alias + "' exists", e);
            return true;
        }
    }

    /**
     * An English analyzer, since {@code standard} neither stems nor drops stopwords, and a raw keyword
     * sub-field for exact matching. Both apply only to a newly created physical index.
     */
    String mappingJson(boolean withAlias) {
        return """
            {
              "settings": {
                "index": { "knn": true, "number_of_shards": 1, "number_of_replicas": 0 },
                "analysis": {
                  "analyzer": {
                    "psa_text": {
                      "type": "custom",
                      "tokenizer": "standard",
                      "filter": ["lowercase", "english_possessive_stemmer", "english_stop", "english_stemmer"]
                    }
                  },
                  "filter": {
                    "english_stop":               { "type": "stop",     "stopwords": "_english_" },
                    "english_stemmer":            { "type": "stemmer",  "language": "english" },
                    "english_possessive_stemmer": { "type": "stemmer",  "language": "possessive_english" }
                  }
                }
              },
              "aliases": %s,
              "mappings": {
                "properties": {
                  "chunkId":    { "type": "keyword" },
                  "entityId":   { "type": "keyword" },
                  "knowledgeId":{ "type": "keyword" },
                  "iterableId": { "type": "keyword" },
                  "sourceType": { "type": "keyword" },
                  "text": {
                    "type": "text", "analyzer": "psa_text",
                    "fields": { "raw": { "type": "keyword", "ignore_above": 256 } }
                  },
                  "title": {
                    "type": "text", "analyzer": "psa_text",
                    "fields": { "raw": { "type": "keyword", "ignore_above": 256 } }
                  },
                  "embedding": {
                    "type": "knn_vector",
                    "dimension": %d,
                    "method": {
                      "name": "hnsw", "engine": "lucene", "space_type": "cosinesimil",
                      "parameters": { "m": 16, "ef_construction": 128 }
                    }
                  },
                  "ordinal":   { "type": "integer" },
                  "tokenCount": { "type": "integer" },
                  "uri":       { "type": "keyword" },
                  "metadata":  { "type": "object", "properties": %s },
                  "indexedAt": { "type": "date" }
                }
              }
            }
            """.formatted(withAlias ? "{ \"" + alias + "\": {} }" : "{}", dimension, METADATA_PROPERTIES);
    }
}
