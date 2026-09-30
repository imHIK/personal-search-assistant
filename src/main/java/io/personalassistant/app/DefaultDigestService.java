package io.personalassistant.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.agent.JsonReplies;
import io.personalassistant.agent.SearchAgent;
import io.personalassistant.agent.prompt.TaskLibrary;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.search.SearchHit;
import io.personalassistant.domain.model.search.SearchQuery;
import io.personalassistant.domain.model.search.SearchResponse;
import io.personalassistant.domain.service.DigestPatch;
import io.personalassistant.domain.service.DigestService;
import io.personalassistant.domain.service.PublishingService;
import io.personalassistant.domain.service.SearchService;
import io.personalassistant.ingestion.schedule.ScheduleResolver;
import io.personalassistant.storage.repository.ChannelRepository;
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

@ApplicationScoped
public class DefaultDigestService implements DigestService {

    private static final Logger LOG = Logger.getLogger(DefaultDigestService.class.getName());

    static final String INDEXED_AT = "indexedAt";

    static final String SOURCE_FIELD = "source";

    private final DigestRepository digests;
    private final SearchService search;
    private final SearchAgent agent;
    private final TaskLibrary library;
    private final ChannelRepository channels;
    private final PublishingService publishing;

    @ConfigProperty(name = "app.digest.new-item-multiplier", defaultValue = "4")
    int newItemMultiplier;

    @ConfigProperty(name = "app.console.url", defaultValue = "http://localhost:8080")
    String consoleUrl;

    @Inject
    public DefaultDigestService(DigestRepository digests, SearchService search, SearchAgent agent,
                                TaskLibrary library, ChannelRepository channels,
                                PublishingService publishing) {
        this.digests = digests;
        this.search = search;
        this.agent = agent;
        this.library = library;
        this.channels = channels;
        this.publishing = publishing;
    }

    @Override
    public Digest create(Digest digest) {
        requireChannels(digest.channelIds());
        requireValidSchedule(digest.schedule());
        Instant now = Instant.now();
        Digest stored = new Digest(
                digest.id() == null || digest.id().isBlank() ? Ids.digest() : digest.id(),
                digest.name(), digest.query(), digest.knowledgeIds(),
                digest.filters(), digest.window(), digest.schedule(), digest.taskId(), digest.useLlm(),
                digest.topK(),
                digest.collapseDuplicates(), digest.maxChunksPerEntity(), digest.onlyNew(),
                digest.enabled(),
                // A null nextRunAt runs on the next tick rather than a whole interval from now.
                null, now, now, null, digest.channelIds());
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
        if (merged.query() == null || merged.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (merged.name() == null || merged.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (patch.channelIds().present()) {
            requireChannels(merged.channelIds());
        }
        // Only a schedule this edit sends is checked: a cron stored before validation existed must not block
        // the edit that replaces it.
        if (patch.schedule().present()) {
            requireValidSchedule(merged.schedule());
        }
        return digests.save(merged);
    }

    private static void requireValidSchedule(SyncSchedule schedule) {
        if (schedule != null) {
            ScheduleResolver.requireValidCron(schedule.cron());
        }
    }

    /** The runs are both the audit trail and the already-seen set; this clears only the latter. */
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
            String taskError = null;
            if (digest.useLlm() && digest.taskId() != null && !fresh.isEmpty()) {
                try {
                    SearchAgent.TaskResult result = agent.runTask(digest.taskId(), digest.toQuery(), fresh);
                    taskOutput = result.reply();
                    items = annotate(digest, items, result);
                } catch (RuntimeException e) {
                    // The results are real and already found; a failed task costs only its annotations.
                    taskError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    LOG.log(Level.WARNING, "Digest " + id + ": task " + digest.taskId() + " failed", e);
                }
            }
            return record(digest, now, items, taskOutput, candidates, suppressed,
                    outsideWindow(digest, candidates), null, taskError);
        } catch (RuntimeException e) {
            // Recorded as a run: a scheduled job that throws leaves no trace anyone sees.
            LOG.log(Level.WARNING, "Digest " + id + " failed", e);
            return record(digest, now, List.of(), null, 0, 0, 0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
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
     * Over-fetches when onlyNew is set: already-reported hits are dropped after retrieval, and new ones must
     * not be left just below the cut.
     */
    SearchQuery queryFor(Digest digest, Instant now) {
        SearchQuery base = digest.toQuery();
        Duration window = digest.windowDuration();
        if (window == null) {
            return digest.onlyNew() ? widen(base, digest) : base;
        }
        Map<String, Object> filters = new LinkedHashMap<>(base.filters());
        // Indexed since, not created since: an item re-indexed inside the window reappears here, and the
        // already-reported check is what stops it reaching the user.
        filters.put(INDEXED_AT, Map.of("gte", now.minus(window).toString()));
        SearchQuery windowed = new SearchQuery(base.text(), base.knowledgeIds(), Map.copyOf(filters),
                base.topK(), base.mode(), false, base.maxChunksPerEntity(), base.collapseDuplicates());
        return digest.onlyNew() ? widen(windowed, digest) : windowed;
    }

    /**
     * Only on an empty run: recounts without the window, so a corpus indexed once and left alone reads as
     * "widen the look-back" rather than "nothing matched". A failure here costs only the hint.
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
                query.mode(), false, query.maxChunksPerEntity(), query.collapseDuplicates());
    }

    /** Keyed on the entity, not the chunk: chunk ids change when a document is re-chunked or re-indexed. */
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
     * The join is positional: element n of the reply describes {@code result.sources().get(n - 1)}, which
     * differs from the items when a whole-entity task collapsed them. Anything malformed costs only the
     * annotation; taskOutput keeps the reply verbatim.
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
                // A null means the model had nothing to say; storing it would render an empty row.
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
                             String error, String taskError) {
        DigestRun saved = digests.saveRun(new DigestRun(Ids.digestRun(), digest.id(), ranAt, items,
                taskOutput, candidates, suppressed, outsideWindow, error, taskError));
        publish(digest, saved);
        return saved;
    }

    /**
     * Only queues delivery rows, so a broken channel cannot fail the run, and the dedupe key stops a replayed
     * run queuing twice. Failures are logged and swallowed: the run is already recorded.
     */
    private void publish(Digest digest, DigestRun run) {
        if (digest.channelIds().isEmpty() || !DigestMessages.worthSending(run)) {
            return;
        }
        PublishMessage message;
        try {
            message = DigestMessages.forRun(digest, run, consoleUrl);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Digest " + digest.id() + ": could not build the message for run " + run.id(), e);
            return;
        }
        for (String channelId : digest.channelIds()) {
            try {
                publishing.enqueue(channelId, message, new Delivery.Origin(Delivery.Origin.DIGEST_RUN, run.id()),
                        "digestRun:" + run.id() + ":" + channelId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Digest " + digest.id() + ": could not queue run " + run.id()
                        + " for channel " + channelId, e);
            }
        }
    }

    private void requireChannels(List<String> channelIds) {
        for (String channelId : channelIds) {
            if (channels.findById(channelId).isEmpty()) {
                throw new IllegalArgumentException("No channel with id " + channelId);
            }
        }
    }

    private Digest require(String id) {
        return digests.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No digest \"" + id + "\""));
    }
}
