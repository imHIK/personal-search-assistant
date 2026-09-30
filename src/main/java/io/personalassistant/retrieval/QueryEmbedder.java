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
 * Embeds search queries for the read path, with the two things a query embedding needs that an indexing
 * one does not: a cache, and a way to lose the vector without losing the search.
 *
 * <p><strong>Losing the vector degrades, it does not fail.</strong> A query embedding is a hosted call
 * that fails fast on a spent quota (see {@code RateLimitMode}), and it used to propagate — a search
 * with a perfectly good lexical leg returned a 500 because the vector leg could not be built. A
 * {@link Session} now catches that, retrieves lexically instead, and reports why through
 * {@link Session#vectorError()}, the same shape {@code answerError} gives an unavailable LLM. Any
 * failure is caught, not only a rate limit: a bad key or an unreachable endpoint equally leaves the
 * lexical leg intact, and the reason is surfaced rather than swallowed.
 *
 * <p><strong>The cache</strong> is keyed by query text alone, which is sound because the provider — and
 * so the model — is fixed for the life of the process (invariant 5; switching it is a restart and a
 * re-index). It exists because the same texts recur: a digest re-runs its saved search on every tick,
 * a document query re-embeds each of its facets per run, and a user pages or re-filters one query. On a
 * free-tier quota each of those is a call not spent. {@code app.search.query-vector-cache-size=0}
 * disables it.
 */
@ApplicationScoped
public class QueryEmbedder {

    private static final Logger LOG = Logger.getLogger(QueryEmbedder.class.getName());

    private final EmbeddingProvider embeddings;

    /** Most-recently-used query vectors; guarded by its own monitor. */
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

    /** Test-friendly constructor; the {@code @ConfigProperty} field is unreachable from other packages. */
    public QueryEmbedder(EmbeddingProvider embeddings, int cacheSize) {
        this(embeddings);
        this.cacheSize = cacheSize;
    }

    /** One search's worth of embedding. Open one per request; it is not thread-safe and not reusable. */
    public Session session() {
        return new Session();
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
        // Outside the lock: the call is remote, and two concurrent misses on one text cost at most one
        // duplicate embedding, which is cheaper than serialising every search behind a network call.
        Embedding embedding = embeddings.embedQuery(key);
        if (cacheSize > 0) {
            synchronized (cache) {
                cache.put(key, embedding);
            }
        }
        return embedding;
    }

    /**
     * The embedding state of a single search, which may embed several texts (one per facet of a
     * document query). The first refusal switches the rest of the search to lexical: the calls after
     * it would be refused by the same quota, and a result set that mixes hybrid and lexical facets is
     * no worse than one that is lexical throughout.
     */
    public final class Session {

        private String vectorError;

        private Session() {
        }

        /**
         * Retrieve {@code query}, embedding its text first unless it is lexical. When the embedding is
         * refused the query is retrieved as {@link SearchQuery.Mode#LEXICAL} — not handed to the
         * retriever with a null vector, which a hybrid or semantic leg cannot run on.
         */
        public List<SearchHit> retrieve(Retriever retriever, SearchQuery query, int limit) {
            if (query.mode() == SearchQuery.Mode.LEXICAL) {
                return retriever.retrieve(query, null, limit);
            }
            float[] vector = vectorFor(query.text());
            return vector == null
                    ? retriever.retrieve(query.withMode(SearchQuery.Mode.LEXICAL), null, limit)
                    : retriever.retrieve(query, vector, limit);
        }

        /** Why this search ran without its vector leg, or null when it did not. */
        public String vectorError() {
            return vectorError;
        }

        private float[] vectorFor(String text) {
            if (vectorError != null) {
                return null;
            }
            try {
                return embed(text).vector();
            } catch (RuntimeException e) {
                vectorError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOG.log(Level.WARNING, "Query embedding failed; retrieving lexically: " + vectorError);
                return null;
            }
        }
    }
}
