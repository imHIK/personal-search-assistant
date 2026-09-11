package io.personalassistant.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.agent.JsonReplies;
import io.personalassistant.agent.SearchAgent;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.DigestPatch;
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

    /** The field a per-source reply names its source in. Owned by the framework, never by a task. */
    static final String SOURCE_FIELD = "source";

    private final DigestRepository digests;
    private final SearchService search;
    private final SearchAgent agent;
    private final TaskLibrary library;

    /**
     * How many extra candidates a run asks for when it is going to discard already-seen ones. Without
     * over-fetching, a digest whose top results are all familiar reports nothing at all — the new items
     * were there, just below the cut.
     */
    @ConfigProperty(name = "app.digest.new-item-multiplier", defaultValue = "4")
    int newItemMultiplier;

    @Inject
    public DefaultDigestService(DigestRepository digests, SearchService search, SearchAgent agent,
                                TaskLibrary library) {
        this.digests = digests;
        this.search = search;
        this.agent = agent;
        this.library = library;
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
    public Digest update(String id, DigestPatch patch) {
        Digest existing = require(id);
        Digest merged = patch.applyTo(existing).withTouched(Instant.now());
        // The same rules creation enforces: a digest with neither a query nor a document to search by
        // would run forever and find nothing, and one with no name is a blank row in the console. An
        // edit is just as capable of producing either.
        if ((merged.query() == null || merged.query().isBlank())
                && merged.sourceEntityId() == null) {
            throw new IllegalArgumentException("a digest needs either query or sourceEntityId");
        }
        if (merged.name() == null || merged.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return digests.save(merged);
    }

    /**
     * Forget what this digest has already reported, without deleting the record of it.
     *
     * <p>The two are separable on purpose. The history is both the audit trail and the already-seen
     * set; deleting runs to replay a backlog would take the trail with it, and a user widening a query
     * wants the backlog, not amnesia about what was sent last week.
     */
    @Override
    public Digest resetHistory(String id) {
        return digests.save(require(id).withHistoryResetAt(Instant.now()));
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
            int candidates = response.hits().size();
            int suppressed = Math.max(candidates - fresh.size(), 0);

            List<DigestRun.Item> items = project(fresh);
            String taskOutput = null;
            if (digest.taskId() != null && !fresh.isEmpty()) {
                SearchAgent.TaskResult result =
                        agent.runTaskWithSources(digest.taskId(), digest.toQuery(), fresh, Map.of());
                taskOutput = result.reply();
                items = annotate(digest, items, result);
            }
            return record(digest, now, items, taskOutput, candidates, suppressed,
                    outsideWindow(digest, candidates), null);
        } catch (RuntimeException e) {
            // A scheduled job that throws leaves no trace a user will ever see. Recording the failure
            // as a run is what makes "this digest has been broken for a week" visible in the console.
            LOG.log(Level.WARNING, "Digest " + id + " failed", e);
            return record(digest, now, List.of(), null, 0, 0, 0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @Override
    public List<DigestRun> runs(String digestId, int limit, int offset) {
        return digests.findRuns(digestId, Math.max(limit, 1), Math.max(offset, 0));
    }

    @Override
    public Optional<DigestRun> run(String digestId, String runId) {
        return digests.findRun(runId).filter(run -> run.digestId().equals(digestId));
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

    /**
     * How much the look-back window cost this run, counted only when the run came back empty-handed.
     *
     * <p>The window is a range filter on {@code indexedAt} — <em>indexed</em> since, not <em>written</em>
     * since. For a feed that is re-walked constantly, which is the case digests were built for, those are
     * close enough. For a corpus that was ingested once and then left alone they are not: a week later
     * every chunk sits outside a one-day window, and the digest is empty on every run, forever, while
     * reporting the same "nothing matched" as a query with a typo in it. Nothing in {@code candidates}
     * or {@code suppressed} can tell those apart, because both are zero either way.
     *
     * <p>So on the empty path only — where there is no result to slow down and one more search is
     * affordable — the same query runs again without the window, and the count goes on the run. A
     * failure here costs the hint and nothing else: the run itself already succeeded.
     */
    private int outsideWindow(Digest digest, int candidates) {
        if (candidates > 0 || digest.windowDuration() == null) {
            return 0;
        }
        try {
            return search.search(widen(digest.toQuery(), digest)).hits().size();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Digest " + digest.id() + ": unwindowed recount failed", e);
            return 0;
        }
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
        Set<String> seen = digests.reportedEntityIds(digest.id(), digest.historyResetAt());
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

    private List<DigestRun.Item> project(List<SearchHit> hits) {
        List<DigestRun.Item> items = new ArrayList<>(hits.size());
        for (SearchHit hit : hits) {
            items.add(new DigestRun.Item(hit.entityId(), hit.chunkId(), hit.title(), hit.uri(),
                    hit.score(), hit.snippet()));
        }
        return items;
    }

    /**
     * Attach what the task said about each item, for a task whose reply describes its sources one by
     * one.
     *
     * <p>The join is positional and the ordering is a contract the prompt builder already keeps:
     * sources are numbered from 1 in the order they were rendered, so element {@code n} of the reply
     * describes {@code result.sources().get(n - 1)}. That list has to come back from the agent rather
     * than being assumed to equal {@code hits}, because a whole-entity task collapses several chunks of
     * one document into a single source before numbering them.
     *
     * <p>Everything here degrades to "no annotations" rather than failing. A model that ignored the
     * requested shape, wrapped its object in prose, or numbered a source that was cut for budget should
     * cost the annotation and nothing else: the items are real search results and worth showing, and
     * {@code taskOutput} still holds the reply verbatim for whoever wants to see why.
     */
    private List<DigestRun.Item> annotate(Digest digest, List<DigestRun.Item> items,
                                          SearchAgent.TaskResult result) {
        String array = library.resolve(digest.taskId()).spec().annotatesArray();
        if (array == null) {
            return items;
        }
        JsonNode root = JsonReplies.object(result.reply()).orElse(null);
        JsonNode entries = root == null ? null : root.get(array);
        if (entries == null || !entries.isArray()) {
            LOG.fine(() -> "Digest " + digest.id() + ": task reply carried no \"" + array + "\" array");
            return items;
        }

        // Where each source sits in the run's item list, so a reply's source number reaches the right
        // item even when the task collapsed the hits before numbering them.
        Map<String, Integer> indexByEntity = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            indexByEntity.putIfAbsent(items.get(i).entityId(), i);
        }

        List<DigestRun.Item> annotated = new ArrayList<>(items);
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                continue;
            }
            JsonNode source = entry.get(SOURCE_FIELD);
            if (source == null || !source.canConvertToInt()) {
                continue;
            }
            int ordinal = source.asInt();
            if (ordinal < 1 || ordinal > result.sources().size()) {
                continue;
            }
            String entityId = result.sources().get(ordinal - 1).entityId();
            Integer target = entityId == null ? null : indexByEntity.get(entityId);
            if (target == null) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            entry.fields().forEachRemaining(field -> {
                // A null is the task's way of saying "nothing to report here"; storing it would make
                // the console render an empty row for a field the model deliberately left out.
                if (!SOURCE_FIELD.equals(field.getKey()) && !field.getValue().isNull()) {
                    values.put(field.getKey(), plain(field.getValue()));
                }
            });
            if (!values.isEmpty()) {
                annotated.set(target, annotated.get(target).withAnnotations(values));
            }
        }
        return annotated;
    }

    /** Jackson node to a plain value the storage layer and the API can both carry. */
    private static Object plain(JsonNode value) {
        if (value.isNumber()) {
            return value.isIntegralNumber() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asText();
    }

    private DigestRun record(Digest digest, Instant ranAt, List<DigestRun.Item> items,
                             String taskOutput, int candidates, int suppressed, int outsideWindow,
                             String error) {
        return digests.saveRun(new DigestRun(Ids.digestRun(), digest.id(), ranAt, items, taskOutput,
                candidates, suppressed, outsideWindow, error));
    }

    private Digest require(String id) {
        return digests.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No digest \"" + id + "\""));
    }
}
