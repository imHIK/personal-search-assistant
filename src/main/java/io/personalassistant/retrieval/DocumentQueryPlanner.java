package io.personalassistant.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.agent.JsonReplies;
import io.personalassistant.agent.SearchAgent;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns "find everything like this document" into a handful of ordinary queries.
 *
 * <p><strong>Why not just search with the document's text.</strong> Passing a whole document as the
 * query fails twice over. The lexical leg degenerates: {@code multi_match} with
 * {@code minimum_should_match} tuned for a sentence behaves unpredictably across several hundred words,
 * and every incidental term becomes scoreable. The vector leg degenerates differently: one embedding of
 * a long document is a centroid sitting near nothing in particular, and the match is <em>asymmetric</em>
 * — a CV and a job posting describe the same work in different registers, so their vectors are further
 * apart than the subject matter suggests.
 *
 * <p>The fix is to ask a model for a few short queries written in the register of the corpus being
 * searched, then run them as normal queries and fuse. Each facet is a well-formed query of the shape
 * both retrieval legs were tuned for, and fusing rewards a document that matches several facets over one
 * that matches a single facet very well.
 *
 * <p>Results are cached by the entity's checksum, which is already the corpus-wide change signal
 * (invariant 3): edit the document and the cache key moves on its own, with nothing to invalidate.
 */
@ApplicationScoped
public class DocumentQueryPlanner {

    private static final Logger LOG = Logger.getLogger(DocumentQueryPlanner.class.getName());

    /** The catalogue task that derives facets. Its prompt, profile and budgets live in prompts.json. */
    static final String TASK_ID = "document-facets";

    private final EntityRepository entities;
    private final SearchAgent agent;
    private final SearchIndex index;

    /** checksum → facets. Bounded by the number of distinct documents ever used as a query. */
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    /**
     * Ceiling on the source document's length. A document far past this is a mistake — someone pointed
     * at a whole book rather than a CV — and silently truncating would produce facets drawn from
     * whichever pages happened to come first, which looks like a working search returning bad results.
     * Reported instead, as a 400.
     */
    @ConfigProperty(name = "app.search.document-query.max-chars", defaultValue = "40000")
    int maxChars;

    /**
     * Ceiling on derived facets. Each one costs a full retrieval round trip, so an unbounded list would
     * let a model's verbosity decide how expensive a search is.
     */
    @ConfigProperty(name = "app.search.document-query.max-facets", defaultValue = "8")
    int maxFacets;

    /**
     * Cap on chunks read back when the document's text has to be reassembled from the index. Bounds a
     * long source document without needing to know its length in advance.
     */
    @ConfigProperty(name = "app.search.document-query.max-chunks", defaultValue = "50")
    int maxChunks;

    @Inject
    public DocumentQueryPlanner(EntityRepository entities, SearchAgent agent, SearchIndex index) {
        this.entities = entities;
        this.agent = agent;
        this.index = index;
    }

    /** Test-friendly constructor setting the tunables explicitly (CDI uses the injected one). */
    public DocumentQueryPlanner(EntityRepository entities, SearchAgent agent, SearchIndex index,
                                int maxChars, int maxFacets) {
        this(entities, agent, index);
        this.maxChars = maxChars;
        this.maxFacets = maxFacets;
        this.maxChunks = 50;
    }

    /**
     * The queries to run for {@code query}, in order.
     *
     * <p>Never returns empty: if the model is unavailable or its reply cannot be read, this falls back
     * to the document's own opening text as a single query. That is a weaker search than the facets
     * would have given, but it is a search — failing the request outright would turn a degraded model
     * into a broken feature.
     *
     * @throws NoSuchElementException   if the entity does not exist (a 404 at the resource)
     * @throws IllegalArgumentException if it carries no usable text, or far too much (a 400)
     */
    public List<String> facets(SearchQuery query) {
        Entity entity = entities.findById(query.sourceEntityId())
                .orElseThrow(() -> new NoSuchElementException(
                        "No entity \"" + query.sourceEntityId() + "\" to search by"));
        String text = documentText(entity);

        String cacheKey = entity.checksum() == null ? entity.id() : entity.checksum();
        List<String> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> facets = derive(entity, query);
        if (facets.isEmpty()) {
            // The fallback, deliberately not cached: the next call may find the model available.
            return List.of(opening(text));
        }
        cache.put(cacheKey, facets);
        return facets;
    }

    /**
     * The document's text, validated.
     *
     * <p>Falls back to reassembling the entity's chunks when it carries no inline text. That is not an
     * edge case: {@code LOCAL_FS} — the obvious place to keep a CV — always stores a {@code fileRef} and
     * never inline text, so without this the feature could not read the most ordinary source there is.
     * Re-parsing the file here instead would duplicate the indexing stage on the read path and would
     * fail for a source whose bytes are no longer local.
     */
    private String documentText(Entity entity) {
        String text = entity.content() == null ? null : entity.content().text();
        if (text == null || text.isBlank()) {
            text = String.join("\n\n", index.chunkTextsByEntity(entity.id(), Math.max(maxChunks, 1)));
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("Entity \"" + entity.id()
                    + "\" has no readable text to search by. It carries no inline text and has no"
                    + " indexed chunks — index it first.");
        }
        if (text.length() > maxChars) {
            throw new IllegalArgumentException("Document is " + text.length()
                    + " characters, over the " + maxChars + " limit for searching by document"
                    + " (app.search.document-query.max-chars)");
        }
        return text;
    }

    private List<String> derive(Entity entity, SearchQuery query) {
        try {
            // A synthetic hit pointing at the entity: the task declares sourceText=ENTITY, so the
            // prompt builder renders the whole document rather than any one chunk of it.
            SearchHit source = new SearchHit(entity.id() + "_source", entity.id(), entity.knowledgeId(),
                    0, entity.title(), null, null, entity.uri(), 1.0, Map.of());
            // The cap is enforced in parse() regardless; telling the model keeps it from producing
            // work that is only going to be discarded.
            String reply = agent.runTask(TASK_ID, query, List.of(source),
                    Map.of("maxFacets", String.valueOf(Math.max(maxFacets, 1))));
            return parse(reply);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not derive query facets from entity " + entity.id()
                    + "; falling back to the document's opening text", e);
            return List.of();
        }
    }

    /** Read the {@code facets} array, dropping blanks and duplicates and capping the count. */
    private List<String> parse(String reply) {
        Optional<JsonNode> object = JsonReplies.object(reply);
        if (object.isEmpty()) {
            LOG.warning("Facet task returned no readable JSON object; falling back");
            return List.of();
        }
        JsonNode facets = object.get().path("facets");
        if (!facets.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode facet : facets) {
            String value = facet.isTextual() ? facet.asText().trim() : "";
            if (!value.isEmpty()) {
                out.add(value);
            }
            if (out.size() >= Math.max(maxFacets, 1)) {
                break;
            }
        }
        return List.copyOf(new ArrayList<>(out));
    }

    /** Enough of the document to be a usable single query when facet derivation failed. */
    private static String opening(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 600 ? trimmed : trimmed.substring(0, 600);
    }
}
