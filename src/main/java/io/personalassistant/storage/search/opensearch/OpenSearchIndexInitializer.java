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
 * Ensures the versioned chunks index ({@code chunks_v3_768}) and its alias ({@code chunks}) exist at
 * startup, with the hybrid mapping (BM25 {@code text} + {@code knn_vector} embedding). The app
 * always talks to the alias, so a re-index into a new physical index + alias flip is a
 * zero-downtime operation. Creation is skipped if the physical index already exists.
 *
 * <p><strong>v2 → v3.</strong> The bump is the analysis change: {@code text}/{@code title} moved from the
 * {@code standard} analyzer to a stemming, stopword-filtering English analyzer and gained a {@code raw}
 * keyword sub-field. Analysis cannot be changed on a live index, so a new physical index is the only way
 * to adopt it — hence the version in the name. An existing {@code chunks_v2_768} keeps working exactly as
 * before; nothing here touches or deletes it.
 */
@Singleton
public class OpenSearchIndexInitializer {

    private static final Logger LOG = Logger.getLogger(OpenSearchIndexInitializer.class.getName());
    private static final String PHYSICAL_INDEX = "chunks_v3_768";

    /**
     * Explicit types for the metadata facets that filters compare on. {@code metadata} stays a dynamic
     * object — carrying arbitrary connector facets is the whole point of it — but the fields a range
     * filter targets cannot be left to dynamic inference, because inference is decided by whichever
     * document happens to be indexed first. A posting whose {@code compMin} is absent, or one board
     * emitting it as a string, would type the field as {@code text} for the life of the index, and every
     * later numeric comparison would silently be lexicographic or match nothing at all.
     *
     * <p>Adding sub-fields to an existing object mapping is an <em>additive</em> change, which OpenSearch
     * accepts in place — no alias flip, no re-index, invariant 5 untouched (the embedding width does not
     * move). {@link #ensureMetadataMapping()} applies it on every boot so an index created before these
     * fields existed picks them up. Only fields not yet present are affected; a field that already has a
     * conflicting dynamic type is reported rather than forced, since changing it really would need a new
     * index.
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
              "compCurrency":{ "type": "keyword" },
              "remote":      { "type": "boolean" },
              "sourceRank":  { "type": "integer" },
              "compMin":     { "type": "long" },
              "compMax":     { "type": "long" },
              "postedAt":    { "type": "date" }
            }""";

    private final RestClient client;
    private final String alias;
    private final int dimension;

    @Inject
    public OpenSearchIndexInitializer(RestClient client,
                                      @ConfigProperty(name = "opensearch.index.chunks",
                                              defaultValue = "chunks") String alias,
                                      // No defaultValue on purpose: invariant 5 bakes this width into the
                                      // knn_vector mapping, and a guessed default silently builds an index
                                      // that no provider's vectors fit. Missing => loud startup failure.
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
            // OpenSearch may simply not be running in this environment; log and continue.
            LOG.log(Level.WARNING, "Could not reach OpenSearch to ensure index; will retry on first use", e);
            return;
        }
        ensureMetadataMapping();
    }

    /**
     * Add any missing typed {@code metadata} sub-fields to an index that already exists. Additive
     * mapping updates are accepted in place, and this is idempotent — re-sending a property identical to
     * the stored one is a no-op — so it is safe on every boot.
     *
     * <p>A failure here is logged, never fatal: the app still works with dynamically-typed metadata, it
     * just cannot be trusted for range comparisons, and refusing to start would be a far worse outcome
     * than a degraded filter.
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
        // Only claim the alias if nothing else holds it. Writing through an alias that resolves to two
        // indices fails outright, and reading through one silently returns each chunk twice — so on a
        // version bump the new index is created without the alias and the operator does the swap when the
        // re-index is verified. That is the documented flow; doing it automatically would repoint live
        // search at an empty index.
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

    /** True if the alias already resolves to some index. Treated as "taken" on any error — fail safe. */
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
     * The index definition. Two analysis choices are load-bearing.
     *
     * <p><strong>An English analyzer, not {@code standard}.</strong> {@code standard} only tokenizes and
     * lowercases, so "holidays" and "holiday" are different terms and every stopword in a
     * natural-language query is a scoreable term. That is half of why a conversational query used to rank
     * documents matching "give"/"all"/"this"/"year"; the other half is the query shape
     * ({@code app.search.lexical.minimum-should-match}). Stemming plus {@code _english_} stopwords fixes
     * the term side.
     *
     * <p><strong>A {@code raw} keyword sub-field.</strong> Neither {@code text} nor {@code title} had one,
     * so exact-value matching, sorting and aggregating on a title were all impossible. Capped at
     * {@code ignore_above} so a long chunk body does not blow up the doc-values.
     *
     * <p>Both are mapping changes, so they only take effect on a newly created physical index — an
     * existing one keeps whatever analyzer it was built with (invariant: bump the index name and
     * re-index).
     */
    // Package-private so the mapping can be asserted without a client.
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
