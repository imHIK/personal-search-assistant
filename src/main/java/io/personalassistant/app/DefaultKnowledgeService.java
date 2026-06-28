package io.personalassistant.app;

import io.personalassistant.common.Errors;
import io.personalassistant.common.id.Ids;
import io.personalassistant.domain.model.Cursor;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.DiscoveryStatus;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.CursorStatus;
import io.personalassistant.domain.model.enums.DiscoveryTrigger;
import io.personalassistant.domain.model.enums.KnowledgeStatus;
import io.personalassistant.domain.service.KnowledgeService;
import io.personalassistant.ingestion.connector.ConnectorRegistry;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.storage.repository.CursorRepository;
import io.personalassistant.storage.repository.DiscoveryStatusRepository;
import io.personalassistant.storage.repository.EntityRepository;
import io.personalassistant.storage.repository.KnowledgeRepository;
import io.personalassistant.storage.search.SearchIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default knowledge lifecycle orchestration. On {@link #add} it verifies the connector, sets the
 * anchor to "now" (the boundary between backward backfill and forward incremental), discovers the
 * source's iterables, and creates one backward (if backfill is enabled) and one forward cursor per
 * iterable — then flips the knowledge to {@code ACTIVE} so the ingestion loop picks the cursors up.
 */
@ApplicationScoped
public class DefaultKnowledgeService implements KnowledgeService {

    private static final Logger LOG = Logger.getLogger(DefaultKnowledgeService.class.getName());

    private final KnowledgeRepository knowledge;
    private final CursorRepository cursors;
    private final EntityRepository entities;
    private final ConnectorRegistry connectors;
    private final SearchIndex index;
    private final DiscoveryStatusRepository discoveryStatus;

    @Inject
    public DefaultKnowledgeService(KnowledgeRepository knowledge, CursorRepository cursors,
                                   EntityRepository entities, ConnectorRegistry connectors,
                                   SearchIndex index, DiscoveryStatusRepository discoveryStatus) {
        this.knowledge = knowledge;
        this.cursors = cursors;
        this.entities = entities;
        this.connectors = connectors;
        this.index = index;
        this.discoveryStatus = discoveryStatus;
    }

    @Override
    public Knowledge add(NewKnowledge request) {
        Instant now = Instant.now();
        Knowledge.Config config = request.config() != null ? request.config() : Knowledge.Config.defaults();
        Knowledge draft = new Knowledge(
                Ids.knowledge(),
                request.name(),
                new Knowledge.ConnectorDetails(request.type(), request.auth() == null ? java.util.Map.of() : request.auth()),
                request.inputs() == null ? java.util.Map.of() : request.inputs(),
                config,
                now,
                KnowledgeStatus.DRAFT,
                null,
                Knowledge.Stats.zero(),
                now,
                now);
        knowledge.save(draft); // persist as DRAFT first, so any activation failure is recorded against a real record

        // Verify + discover + create cursors can all fail (bad credentials, unreachable source,
        // discovery error). Any failure parks the knowledge in ERROR with the reason captured, rather
        // than throwing it away — the user can see why it failed and retry.
        try {
            SourceConnector connector = connectors.get(request.type());
            connector.verify(draft);                                  // throws on bad credentials/inputs
            List<SourceIterable> iterables = discover(draft, DiscoveryTrigger.ACTIVATION); // throws if not enumerable
            DirCounts created = createCursors(draft, iterables);
            recordDiscovery(draft, DiscoveryTrigger.ACTIVATION, iterables.size(),
                    created, DirCounts.zero(), DirCounts.zero());
            knowledge.updateStatus(draft.id(), KnowledgeStatus.ACTIVE);
            LOG.info("Activated knowledge " + draft.id() + " (" + request.type() + ")");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate knowledge " + draft.id()
                    + " (" + request.type() + "); marking ERROR", e);
            knowledge.markError(draft.id(), Errors.summary(e));
        }
        return knowledge.findById(draft.id()).orElse(draft);
    }

    @Override
    public Optional<Knowledge> get(String id) {
        return knowledge.findById(id);
    }

    @Override
    public List<Knowledge> list() {
        return knowledge.findAll();
    }

    @Override
    public void pause(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.PAUSED);
        cursors.suspendByKnowledge(id); // park claimable cursors so they can't starve active knowledge
    }

    @Override
    public void resume(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.ACTIVE);
        cursors.resumeByKnowledge(id); // re-arm cursors parked while paused
    }

    @Override
    public void delete(String id) {
        knowledge.updateStatus(id, KnowledgeStatus.DELETED); // stop scheduling immediately
        index.deleteByKnowledge(id);                          // remove derived chunks
        entities.deleteByKnowledge(id);                       // remove canonical entities
        cursors.deleteByKnowledge(id);                        // remove cursors
        discoveryStatus.deleteByKnowledge(id);                // remove discovery-status record(s)
        knowledge.delete(id);                                 // finally drop the knowledge record
    }

    @Override
    public int triggerSync(String id) {
        return cursors.armForwardCursors(id);
    }

    @Override
    public int reconcileCursors(String id) {
        Optional<Knowledge> known = knowledge.findById(id);
        if (known.isEmpty() || known.get().status() != KnowledgeStatus.ACTIVE) {
            return 0;
        }
        Knowledge kn = known.get();
        // One discover serves all three reconciliation steps below (records its outcome/failure).
        List<SourceIterable> iterables = discover(kn, DiscoveryTrigger.RECONCILE);

        DirCounts created = createCursors(kn, iterables);
        DirCounts revived = reviveReappearedIterables(kn, iterables);
        DirCounts retired = retireDeletedIterables(kn, iterables);

        recordDiscovery(kn, DiscoveryTrigger.RECONCILE, iterables.size(), created, revived, retired);

        if (created.total() > 0 || revived.total() > 0 || retired.total() > 0) {
            LOG.info("Reconcile for knowledge " + id + ": +" + created.total() + " new, "
                    + revived.total() + " revived, " + retired.total() + " retired cursor(s)");
        }
        return created.total();
    }

    /** Revive cursors retired for an iterable that has since reappeared, refreshing their attributes. */
    private DirCounts reviveReappearedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Map<String, Map<String, Object>> live = new HashMap<>();
        iterables.forEach(it -> live.put(it.iterableId(), it.attributes()));
        int backward = 0;
        int forward = 0;
        for (Cursor c : cursors.findByKnowledge(kn.id())) {
            if (c.status() == CursorStatus.RETIRED && live.containsKey(c.iterableId())
                    && cursors.revive(c.id(), live.get(c.iterableId()))) {
                if (c.direction() == CursorDirection.BACKWARD) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }
        return new DirCounts(backward, forward);
    }

    /**
     * Retire cursors whose iterable no longer exists at the source, and purge that iterable's
     * indexed data (chunks) and canonical entities first. Idempotent: already-retired or
     * currently-running cursors are skipped and caught on a later pass.
     */
    private DirCounts retireDeletedIterables(Knowledge kn, List<SourceIterable> iterables) {
        Set<String> liveIds = new HashSet<>();
        iterables.forEach(it -> liveIds.add(it.iterableId()));
        List<Cursor> all = cursors.findByKnowledge(kn.id());

        // Distinct iterables that are gone but still have a retire-able (non-RETIRED, non-running) cursor.
        Set<String> goneIterables = new LinkedHashSet<>();
        for (Cursor c : all) {
            if (!liveIds.contains(c.iterableId())
                    && c.status() != CursorStatus.RETIRED && c.status() != CursorStatus.IN_PROGRESS) {
                goneIterables.add(c.iterableId());
            }
        }
        // Purge derived chunks then canonical entities (same order as the knowledge-delete cascade).
        for (String iterableId : goneIterables) {
            index.deleteByIterable(kn.id(), iterableId);
            entities.deleteByKnowledgeAndIterable(kn.id(), iterableId);
        }
        int backward = 0;
        int forward = 0;
        for (Cursor c : all) {
            if (goneIterables.contains(c.iterableId()) && cursors.retire(c.id())) {
                if (c.direction() == CursorDirection.BACKWARD) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }
        return new DirCounts(backward, forward);
    }

    /**
     * Run {@code connector.discover}, recording a {@code FAILED} discovery status for each of the
     * knowledge's active grabber directions before rethrowing — so a failed enumeration is always
     * inspectable from the {@code discovery} collection (not just the log). On success the caller
     * records the {@code OK} runs once it knows the per-direction cursor counts.
     */
    private List<SourceIterable> discover(Knowledge kn, DiscoveryTrigger trigger) {
        SourceConnector connector = connectors.get(kn.connectorDetails().type());
        try {
            return connector.discover(kn);
        } catch (RuntimeException e) {
            String error = Errors.summary(e);
            for (CursorDirection direction : activeGrabberDirections(kn)) {
                discoveryStatus.record(DiscoveryStatus.Run.failed(kn.id(), direction, trigger, error));
            }
            throw e;
        }
    }

    /** Record one discovery status per active grabber direction, attributing each its own counts. */
    private void recordDiscovery(Knowledge kn, DiscoveryTrigger trigger, int iterablesFound,
                                 DirCounts created, DirCounts revived, DirCounts retired) {
        for (CursorDirection direction : activeGrabberDirections(kn)) {
            discoveryStatus.record(DiscoveryStatus.Run.ok(kn.id(), direction, trigger, iterablesFound,
                    new DiscoveryStatus.Counts(created.of(direction), revived.of(direction),
                            retired.of(direction))));
        }
    }

    /**
     * The grabber directions this knowledge actually runs — and therefore the discovery records it
     * has: a forward grabber whenever the source supports it, and a backward grabber only when the
     * source supports it <em>and</em> backfill is enabled. Mirrors exactly which cursors
     * {@link #createCursors} makes, so a record exists per real grabber and no phantom one.
     */
    private List<CursorDirection> activeGrabberDirections(Knowledge kn) {
        var supported = connectors.get(kn.connectorDetails().type()).supportedDirections();
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        List<CursorDirection> directions = new ArrayList<>();
        if (supported.contains(CursorDirection.FORWARD)) {
            directions.add(CursorDirection.FORWARD);
        }
        if (backfill && supported.contains(CursorDirection.BACKWARD)) {
            directions.add(CursorDirection.BACKWARD);
        }
        return directions;
    }

    /**
     * Create cursors for every (iterable × supported direction) the source exposes. Idempotent:
     * deterministic cursor ids + {@code insertIfAbsent} mean re-running only adds cursors for
     * iterables that appeared since last time. Returns the newly-created cursors split by direction.
     */
    private DirCounts createCursors(Knowledge kn, List<SourceIterable> iterables) {
        var supported = connectors.get(kn.connectorDetails().type()).supportedDirections();
        boolean backfill = kn.config().backfill() != null && kn.config().backfill().enabled();
        int backward = 0;
        int forward = 0;
        for (SourceIterable iterable : iterables) {
            // Backward (history) only if the source supports it AND backfill is enabled.
            if (backfill && supported.contains(CursorDirection.BACKWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.BACKWARD))) {
                backward++;
            }
            // Forward (incremental) whenever the source supports it.
            if (supported.contains(CursorDirection.FORWARD)
                    && cursors.insertIfAbsent(newCursor(kn, iterable, CursorDirection.FORWARD))) {
                forward++;
            }
        }
        return new DirCounts(backward, forward);
    }

    /** Per-direction tallies for the grabber-scoped discovery records. */
    private record DirCounts(int backward, int forward) {
        static DirCounts zero() {
            return new DirCounts(0, 0);
        }

        int of(CursorDirection direction) {
            return direction == CursorDirection.BACKWARD ? backward : forward;
        }

        int total() {
            return backward + forward;
        }
    }

    private Cursor newCursor(Knowledge kn, SourceIterable iterable, CursorDirection direction) {
        return new Cursor(
                Ids.cursorFor(kn.id(), iterable.iterableId(), direction.name()),
                kn.id(),
                iterable.iterableId(),
                iterable.attributes(), // snapshot the grab() inputs so the runner needn't re-discover
                direction,
                CursorPosition.start(),
                CursorStatus.AVAILABLE,
                null,
                Cursor.Retry.zero(),
                Cursor.Stats.zero(),
                new Cursor.Scope(kn.connectorDetails().type()));
    }
}
