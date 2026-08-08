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
 * Ensures the versioned chunks index ({@code chunks_v1}) and its alias ({@code chunks}) exist at
 * startup, with the hybrid mapping (BM25 {@code text} + {@code knn_vector} embedding). The app
 * always talks to the alias, so a future re-index into {@code chunks_v2} + alias flip is a
 * zero-downtime operation. Creation is skipped if the physical index already exists.
 */
@Singleton
public class OpenSearchIndexInitializer {

    private static final Logger LOG = Logger.getLogger(OpenSearchIndexInitializer.class.getName());
    private static final String PHYSICAL_INDEX = "chunks_v2_768";

    private final RestClient client;
    private final String alias;
    private final int dimension;

    @Inject
    public OpenSearchIndexInitializer(RestClient client,
                                      @ConfigProperty(name = "opensearch.index.chunks",
                                              defaultValue = "chunks") String alias,
                                      @ConfigProperty(name = "app.embedding.dimension",
                                              defaultValue = "384") int dimension) {
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
            } else {
                LOG.log(Level.WARNING, "Unexpected response checking index existence", notFound);
            }
        } catch (IOException e) {
            // OpenSearch may simply not be running in this environment; log and continue.
            LOG.log(Level.WARNING, "Could not reach OpenSearch to ensure index; will retry on first use", e);
        }
    }

    private void createIndex() {
        Request request = new Request("PUT", "/" + PHYSICAL_INDEX);
        request.setJsonEntity(mappingJson());
        try {
            client.performRequest(request);
            LOG.info("Created OpenSearch index " + PHYSICAL_INDEX + " with alias " + alias);
        } catch (ResponseException already) {
            LOG.fine("Index creation race ignored: " + already.getMessage());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to create OpenSearch index", e);
        }
    }

    private String mappingJson() {
        return """
            {
              "settings": { "index": { "knn": true, "number_of_shards": 1, "number_of_replicas": 0 } },
              "aliases": { "%s": {} },
              "mappings": {
                "properties": {
                  "chunkId":    { "type": "keyword" },
                  "entityId":   { "type": "keyword" },
                  "knowledgeId":{ "type": "keyword" },
                  "iterableId": { "type": "keyword" },
                  "sourceType": { "type": "keyword" },
                  "text":       { "type": "text", "analyzer": "standard" },
                  "title":      { "type": "text", "analyzer": "standard" },
                  "embedding": {
                    "type": "knn_vector",
                    "dimension": %d,
                    "method": {
                      "name": "hnsw", "engine": "lucene", "space_type": "cosinesimil",
                      "parameters": { "m": 16, "ef_construction": 128 }
                    }
                  },
                  "ordinal":   { "type": "integer" },
                  "uri":       { "type": "keyword" },
                  "metadata":  { "type": "object" },
                  "indexedAt": { "type": "date" }
                }
              }
            }
            """.formatted(alias, dimension);
    }
}
