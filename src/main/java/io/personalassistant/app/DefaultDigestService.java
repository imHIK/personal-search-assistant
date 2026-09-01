package io.personalassistant.app;

import io.personalassistant.agent.SearchAgent;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.DigestService;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.storage.repository.DigestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs saved searches and records what they found.
 *
 * <p>A run is three steps: bound the search to the digest's look-back window, drop anything an earlier
 * run already reported, and optionally hand the survivors to a prompt-catalogue task.
 */
@ApplicationScoped
public class DefaultDigestService implements DigestService {

    private static final Logger LOG = Logger.getLogger(DefaultDigestService.class.getName());

    /** The chunk field a look-back window filters on. Written on every chunk at index time. */
    static final String INDEXED_AT = "indexedAt";

    private final DigestRepository digests;
    private final SearchService search;
    private final SearchAgent agent;

    /**
     * How many extra candidates a run asks for when it is going to discard already-seen ones. Without
     * over-fetching, a digest whose top results are all familiar reports nothing at all — the new items
     * were there, just below the cut.
     */
    @ConfigProperty(name = "app.digest.new-item-multiplier", defaultValue = "4")
    int newItemMultiplier;

    @Inject
    public DefaultDigestService(DigestRepository digests, SearchService search, SearchAgent agent) {
        this.digests = digests;
        this.search = search;
        this.agent = agent;
    }

    @Override
    public Digest create(Digest digest) {
        Instant now = Instant.now();
        Digest stored = new Digest(
                digest.id() == null || digest.id().isBlank() ? Ids.digest() : digest.id(),
                digest.name(), digest.query(), digest.sourceEntityId(), digest.knowledgeIds(),
                digest.filters(), digest.window(), digest.schedule(), digest.taskId(), digest.topK(),
                digest.collapseDuplicates(), digest.maxChunksPerEntity(), digest.onlyNew(),
                digest.enabled(),
                // Left null so the first run happens on the next tick rather than one whole interval
                // from now — a digest you just created and cannot see the output of looks broken.
                null, now, now);
        return digests.save(stored);
    }

    @Override
    public List<Digest> list() {
        return digests.findAll();
    }

    @Override
    public Optional<Digest> get(String id) {
        return digests.findById(id);
    }

    @Override
    public Digest setEnabled(String id, boolean enabled) {
        Digest digest = require(id);
        return digests.save(digest.withEnabled(enabled, Instant.now()));
    }

    @Override
    public void delete(String id) {
        digests.delete(id);
    }

    @Override
    public DigestRun run(String id) {
        Digest digest = require(id);
        Instant now = Instant.now();
        try {
            SearchResponse response = search.search(queryFor(digest, now));
            List<SearchHit> fresh = withoutAlreadyReported(digest, response.hits());
            String taskOutput = runTask(digest, fresh);
            return record(digest, now, fresh, taskOutput, null);
        } catch (RuntimeException e) {
            // A scheduled job that throws leaves no trace a user will ever see. Recording the failure
            // as a run is what makes "this digest has been broken for a week" visible in the console.
            LOG.log(Level.WARNING, "Digest " + id + " failed", e);
            return record(digest, now, List.of(), null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @Override
    public List<DigestRun> runs(String digestId, int limit) {
        return digests.findRuns(digestId, Math.max(limit, 1));
    }

    @Override
    public Optional<DigestRun> latestRun(String digestId) {
        return digests.findLatestRun(digestId);
    }

    /**
     * The digest's search, with its look-back window applied as a range filter on {@code indexedAt}.
     *
     * <p>Over-fetches when {@code onlyNew} is set, because the filtering happens after retrieval: a
     * digest asking for 10 results whose top 10 are all familiar would otherwise report nothing while
     * new items sat just below the cut.
     */
    // Package-private for tests.
    SearchQuery queryFor(Digest digest, Instant now) {
        SearchQuery base = digest.toQuery();
        Duration window = digest.windowDuration();
        if (window == null) {
            return digest.onlyNew() ? widen(base, digest) : base;
        }
        Map<String, Object> filters = new LinkedHashMap<>(base.filters());
        // Note this is "indexed since", not "created since": an item re-indexed inside the window
        // reappears here. The already-reported check below is what stops that reaching the user.
        filters.put(INDEXED_AT, Map.of("gte", now.minus(window).toString()));
        SearchQuery windowed = new SearchQuery(base.text(), base.knowledgeIds(), Map.copyOf(filters),
                base.topK(), base.mode(), false, base.maxChunksPerEntity(), base.collapseDuplicates(),
                base.sourceEntityId());
        return digest.onlyNew() ? widen(windowed, digest) : windowed;
    }

    private SearchQuery widen(SearchQuery query, Digest digest) {
        int wider = digest.topK() * Math.max(newItemMultiplier, 1);
        return new SearchQuery(query.text(), query.knowledgeIds(), query.filters(), wider,
                query.mode(), false, query.maxChunksPerEntity(), query.collapseDuplicates(),
                query.sourceEntityId());
    }

    /**
     * Drop hits whose entity a previous run already reported, then trim back to the digest's topK.
     *
     * <p>Keyed on the <em>entity</em>, not the chunk: a chunk id changes whenever a document is
     * re-chunked or re-indexed, so a chunk-keyed check would re-report documents the user has seen every
     * time retention aged one out and the next walk re-created it.
     */
    private List<SearchHit> withoutAlreadyReported(Digest digest, List<SearchHit> hits) {
        if (!digest.onlyNew()) {
            return hits.size() <= digest.topK() ? hits : hits.subList(0, digest.topK());
        }
        Set<String> seen = digests.reportedEntityIds(digest.id());
        List<SearchHit> fresh = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (hit.entityId() != null && seen.contains(hit.entityId())) {
                continue;
            }
            fresh.add(hit);
            if (fresh.size() >= digest.topK()) {
                break;
            }
        }
        return fresh;
    }

    /** The digest's task over its results, or null. A task failure fails the run, and is recorded. */
    private String runTask(Digest digest, List<SearchHit> hits) {
        if (digest.taskId() == null || hits.isEmpty()) {
            return null;
        }
        return agent.runTask(digest.taskId(), digest.toQuery(), hits);
    }

    private DigestRun record(Digest digest, Instant ranAt, List<SearchHit> hits, String taskOutput,
                             String error) {
        List<DigestRun.Item> items = new ArrayList<>(hits.size());
        for (SearchHit hit : hits) {
            items.add(new DigestRun.Item(hit.entityId(), hit.chunkId(), hit.title(), hit.uri(),
                    hit.score(), hit.snippet()));
        }
        return digests.saveRun(
                new DigestRun(Ids.digestRun(), digest.id(), ranAt, items, taskOutput, error));
    }

    private Digest require(String id) {
        return digests.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No digest \"" + id + "\""));
    }
}
