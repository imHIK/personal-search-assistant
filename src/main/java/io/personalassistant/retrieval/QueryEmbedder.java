package io.personalassistant.retrieval;

import io.personalassistant.domain.model.Embedding;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.indexing.embedding.EmbeddingProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Embeds search queries, caching the vectors and falling back to lexical retrieval when the embedding
 * fails for any reason.
 *
 * <p>The cache is keyed by text alone, which holds because the embedding model is fixed for the life of
 * the process (invariant 5).
 */
@ApplicationScoped
public class QueryEmbedder {

    private static final Logger LOG = Logger.getLogger(QueryEmbedder.class.getName());

    private final EmbeddingProvider embeddings;

    private final Map<String, Embedding> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Embedding> eldest) {
            return size() > cacheSize;
        }
    };

    @ConfigProperty(name = "app.search.query-vector-cache-size", defaultValue = "512")
    int cacheSize;

    @Inject
    public QueryEmbedder(EmbeddingProvider embeddings) {
        this.embeddings = embeddings;
    }

    public QueryEmbedder(EmbeddingProvider embeddings, int cacheSize) {
        this(embeddings);
        this.cacheSize = cacheSize;
    }

    /** @param vectorError why the search ran without its vector leg, or null when it did not */
    public record Retrieval(List<SearchHit> hits, String vectorError) {}

    public Retrieval retrieve(Retriever retriever, SearchQuery query, int limit) {
        if (query.mode() == SearchQuery.Mode.LEXICAL) {
            return new Retrieval(retriever.retrieve(query, null, limit), null);
        }
        float[] vector;
        try {
            vector = embed(query.text()).vector();
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.log(Level.WARNING, "Query embedding failed; retrieving lexically: " + reason);
            // A hybrid or semantic leg cannot run on a null vector.
            return new Retrieval(retriever.retrieve(query.withMode(SearchQuery.Mode.LEXICAL), null, limit),
                    reason);
        }
        return new Retrieval(retriever.retrieve(query, vector, limit), null);
    }

    private Embedding embed(String text) {
        String key = text == null ? "" : text;
        if (cacheSize > 0) {
            synchronized (cache) {
                Embedding hit = cache.get(key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // Outside the lock: two concurrent misses cost one duplicate embedding, not a serialised search.
        Embedding embedding = embeddings.embedQuery(key);
        if (cacheSize > 0) {
            synchronized (cache) {
                cache.put(key, embedding);
            }
        }
        return embedding;
    }
}
